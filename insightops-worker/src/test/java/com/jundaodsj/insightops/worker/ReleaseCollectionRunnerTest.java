package com.jundaodsj.insightops.worker;

import com.jundaodsj.insightops.project.application.ProjectUpdateStore;
import com.jundaodsj.insightops.tool.application.github.GitHubRelease;
import com.jundaodsj.insightops.tool.application.github.GitHubReleaseGateway;
import com.jundaodsj.insightops.tool.application.github.GitHubReleaseResult;
import com.jundaodsj.insightops.tool.application.github.GitHubRepositoryReleaseQuery;
import com.jundaodsj.insightops.tool.application.github.GitHubToolErrorCode;
import com.jundaodsj.insightops.tool.application.github.GitHubToolException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReleaseCollectionRunnerTest {
    private static final Instant NOW = Instant.parse("2026-08-17T09:00:00Z");
    private final ProjectUpdateStore store = mock(ProjectUpdateStore.class);
    private final GitHubReleaseGateway gateway = mock(GitHubReleaseGateway.class);
    private final ReleaseCollectionProperties properties = new ReleaseCollectionProperties();
    private final ProjectUpdateStore.TrackedProject project = new ProjectUpdateStore.TrackedProject(
            UUID.randomUUID(), UUID.randomUUID(), "spring-ai", "spring-projects", "spring-ai", 0);
    private ReleaseCollectionRunner runner;

    @BeforeEach
    void setUp() {
        runner = new ReleaseCollectionRunner(
                store, gateway, properties, Clock.fixed(NOW, ZoneOffset.UTC));
        when(store.claimDueProjects(any(), any(), anyInt())).thenReturn(List.of(project));
    }

    @Test
    void storesNewReleaseEventsAndSchedulesTheNextRegularSync() {
        GitHubRelease release = new GitHubRelease(
                "spring-ai", "Spring AI", "v2.1.0", "Spring AI 2.1.0", NOW,
                "https://github.com/spring-projects/spring-ai/releases/tag/v2.1.0",
                false, "Release notes");
        when(gateway.listRepositoryReleases(any())).thenReturn(new GitHubReleaseResult(List.of(release), NOW));
        when(store.completeSuccessfulSync(eq(project), any(), eq(NOW), any()))
                .thenReturn(new ProjectUpdateStore.SyncResult(1, 1));

        ReleaseCollectionRunner.CycleResult result = runner.collectDueProjects();

        assertThat(result.succeededProjects()).isEqualTo(1);
        assertThat(result.newEvents()).isEqualTo(1);
        verify(store).completeSuccessfulSync(project, List.of(release), NOW, NOW.plus(Duration.ofHours(6)));
        ArgumentCaptor<GitHubRepositoryReleaseQuery> query =
                ArgumentCaptor.forClass(GitHubRepositoryReleaseQuery.class);
        verify(gateway).listRepositoryReleases(query.capture());
        assertThat(query.getValue().repositoryOwner()).isEqualTo("spring-projects");
        assertThat(query.getValue().repositoryName()).isEqualTo("spring-ai");
    }

    @Test
    void rateLimitUsesOneHourRetryWithoutStoppingTheCycle() {
        when(gateway.listRepositoryReleases(any())).thenThrow(new GitHubToolException(
                GitHubToolErrorCode.RATE_LIMITED, new IllegalStateException("GitHub HTTP 429")));

        ReleaseCollectionRunner.CycleResult result = runner.collectDueProjects();

        assertThat(result.failedProjects()).isEqualTo(1);
        ArgumentCaptor<Instant> failedAt = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> nextRetry = ArgumentCaptor.forClass(Instant.class);
        verify(store).completeFailedSync(eq(project), eq("RATE_LIMITED"), any(),
                failedAt.capture(), nextRetry.capture());
        assertThat(Duration.between(failedAt.getValue(), nextRetry.getValue())).isEqualTo(Duration.ofHours(1));
    }
}
