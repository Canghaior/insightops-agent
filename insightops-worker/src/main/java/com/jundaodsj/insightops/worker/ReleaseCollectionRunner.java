package com.jundaodsj.insightops.worker;

import com.jundaodsj.insightops.project.application.ProjectUpdateStore;
import com.jundaodsj.insightops.tool.application.github.GitHubReleaseGateway;
import com.jundaodsj.insightops.tool.application.github.GitHubProjectEventGateway;
import com.jundaodsj.insightops.tool.application.github.GitHubRepositoryReleaseQuery;
import com.jundaodsj.insightops.tool.application.github.GitHubToolErrorCode;
import com.jundaodsj.insightops.tool.application.github.GitHubToolException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
public class ReleaseCollectionRunner {
    private final ProjectUpdateStore store;
    private final GitHubReleaseGateway gateway;
    private final GitHubProjectEventGateway eventGateway;
    private final ReleaseCollectionProperties properties;
    private final Clock clock;

    @Autowired
    public ReleaseCollectionRunner(
            ProjectUpdateStore store,
            GitHubReleaseGateway gateway,
            GitHubProjectEventGateway eventGateway,
            ReleaseCollectionProperties properties) {
        this(store, gateway, eventGateway, properties, Clock.systemUTC());
    }

    ReleaseCollectionRunner(
            ProjectUpdateStore store,
            GitHubReleaseGateway gateway,
            ReleaseCollectionProperties properties,
            Clock clock) {
        this(store, gateway, null, properties, clock);
    }

    ReleaseCollectionRunner(
            ProjectUpdateStore store,
            GitHubReleaseGateway gateway,
            GitHubProjectEventGateway eventGateway,
            ReleaseCollectionProperties properties,
            Clock clock) {
        this.store = store;
        this.gateway = gateway;
        this.eventGateway = eventGateway;
        this.properties = properties;
        this.clock = clock;
    }

    public CycleResult collectDueProjects() {
        Instant startedAt = clock.instant();
        var projects = store.claimDueProjects(
                startedAt,
                Duration.ofMinutes(Math.max(1, properties.getLockMinutes())),
                Math.max(1, properties.getBatchSize()));
        int succeeded = 0;
        int failed = 0;
        int releases = 0;
        int newEvents = 0;
        for (var project : projects) {
            try {
                Duration lockDuration = Duration.ofMinutes(Math.max(1, properties.getLockMinutes()));
                var result = gateway.listRepositoryReleases(new GitHubRepositoryReleaseQuery(
                        project.catalogProjectId(),
                        project.repository(),
                        project.owner(),
                        project.repository(),
                        null,
                        30,
                        false));
                int projectEventCount = 0;
                int storedProjectEvents = 0;
                if (eventGateway != null) {
                    requireLease(store.renewSyncLease(project, "GITHUB_RELEASE",
                            result.releases().size(), 0, clock.instant(), lockDuration));
                    requireLease(store.renewSyncLease(project, "GITHUB_ISSUE",
                            result.releases().size(), 0, clock.instant(), lockDuration));
                    var eventResult = eventGateway.fetch(project.owner(), project.repository(), 50);
                    projectEventCount = eventResult.events().size();
                    storedProjectEvents = store.storeProjectEvents(
                            project, eventResult.events(), eventResult.fetchedAt());
                    requireLease(store.renewSyncLease(project, "FINALIZING",
                            result.releases().size() + projectEventCount,
                            storedProjectEvents, clock.instant(), lockDuration));
                }
                ProjectUpdateStore.SyncResult stored = store.completeSuccessfulSync(
                        project, result.releases(), result.fetchedAt(),
                        result.fetchedAt().plus(Duration.ofHours(
                                Math.max(1, project.syncIntervalHours()))));
                succeeded++;
                releases += stored.releaseCount();
                newEvents += stored.newEventCount() + storedProjectEvents;
            } catch (GitHubToolException exception) {
                failed++;
                Instant failedAt = clock.instant();
                store.completeFailedSync(project, exception.code().name(), exception.getMessage(),
                        failedAt, failedAt.plus(retryDelay(project.consecutiveFailures(), exception.code())));
            } catch (RuntimeException exception) {
                failed++;
                Instant failedAt = clock.instant();
                store.completeFailedSync(project, GitHubToolErrorCode.INTERNAL_ERROR.name(),
                        exception.getClass().getSimpleName(), failedAt,
                        failedAt.plus(retryDelay(project.consecutiveFailures(), GitHubToolErrorCode.INTERNAL_ERROR)));
            }
        }
        return new CycleResult(projects.size(), succeeded, failed, releases, newEvents, startedAt, clock.instant());
    }

    private static void requireLease(boolean renewed) {
        if (!renewed) throw new IllegalStateException("GitHub collection lease was lost");
    }

    private static Duration retryDelay(int previousFailures, GitHubToolErrorCode code) {
        if (code == GitHubToolErrorCode.RATE_LIMITED) return Duration.ofHours(1);
        if (code == GitHubToolErrorCode.VALIDATION_ERROR) return Duration.ofHours(6);
        long minutes = Math.min(60, 5L * (1L << Math.min(previousFailures, 3)));
        return Duration.ofMinutes(minutes);
    }

    public record CycleResult(
            int claimedProjects,
            int succeededProjects,
            int failedProjects,
            int fetchedReleases,
            int newEvents,
            Instant startedAt,
            Instant finishedAt) {
    }
}
