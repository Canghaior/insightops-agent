package com.jundaodsj.insightops.project.application;

import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.tool.application.github.GitHubRelease;
import com.jundaodsj.insightops.tool.application.github.GitHubProjectEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ProjectUpdateStore {

    List<TrackedProject> claimDueProjects(Instant now, Duration lockDuration, int limit);

    default boolean renewSyncLease(
            TrackedProject project, String currentSourceType, int discoveredCount,
            int storedCount, Instant now, Duration lockDuration) {
        return true;
    }

    default int storeProjectEvents(
            TrackedProject project, List<GitHubProjectEvent> events, Instant fetchedAt) {
        return 0;
    }

    SyncResult completeSuccessfulSync(
            TrackedProject project,
            List<GitHubRelease> releases,
            Instant fetchedAt,
            Instant nextSyncAt);

    void completeFailedSync(
            TrackedProject project,
            String errorCode,
            String errorMessage,
            Instant failedAt,
            Instant nextRetryAt);

    UpdatePage listUpdates(
            ActorContext actor,
            int page,
            int size,
            UUID projectId,
            boolean unreadOnly);

    default UpdatePage listUpdates(
            ActorContext actor, int page, int size, UUID projectId, boolean unreadOnly,
            String eventType, String riskLevel, boolean matchedOnly) {
        return listUpdates(actor, page, size, projectId, unreadOnly);
    }

    default List<EventEvidence> searchEvents(
            UUID workspaceId, String query, int limit, List<String> eventTypes) {
        return List.of();
    }

    long unreadCount(ActorContext actor);

    boolean markRead(ActorContext actor, UUID eventId, Instant readAt);

    int markAllRead(ActorContext actor, Instant readAt);

    List<CollectionStatus> collectionStatus(UUID workspaceId);

    boolean requestSync(UUID workspaceId, UUID projectId, Instant now);

    record TrackedProject(
            UUID id,
            UUID workspaceId,
            String catalogProjectId,
            String owner,
            String repository,
            int syncIntervalHours,
            int consecutiveFailures,
            UUID lockToken) {
        public TrackedProject(UUID id, UUID workspaceId, String catalogProjectId,
                              String owner, String repository, int syncIntervalHours,
                              int consecutiveFailures) {
            this(id, workspaceId, catalogProjectId, owner, repository,
                    syncIntervalHours, consecutiveFailures, null);
        }
        public TrackedProject(UUID id, UUID workspaceId, String catalogProjectId,
                              String owner, String repository, int consecutiveFailures) {
            this(id, workspaceId, catalogProjectId, owner, repository, 6, consecutiveFailures, null);
        }
    }

    record SyncResult(int releaseCount, int newEventCount) {
    }

    record ProjectUpdate(
            UUID eventId,
            UUID projectId,
            String projectName,
            String repositoryOwner,
            String eventType,
            String versionTag,
            String title,
            String summary,
            String sourceUrl,
            String state,
            String authorLogin,
            List<String> labels,
            int importance,
            boolean prerelease,
            Instant occurredAt,
            Instant collectedAt,
            boolean read,
            UUID analysisId,
            String analysisStatus,
            String riskLevel,
            String recommendation,
            String intelligenceSummary,
            long matchedRuleCount) {
    }

    record UpdatePage(List<ProjectUpdate> items, int page, int size, long total, long unreadCount) {
    }

    record CollectionStatus(
            UUID projectId,
            String projectName,
            String repositoryOwner,
            String status,
            Instant lastSyncAt,
            Instant nextSyncAt,
            int consecutiveFailures,
            String lastError,
            String currentSourceType,
            Instant heartbeatAt,
            int discoveredCount,
            int storedCount) {
        public CollectionStatus(
                UUID projectId, String projectName, String repositoryOwner, String status,
                Instant lastSyncAt, Instant nextSyncAt, int consecutiveFailures, String lastError) {
            this(projectId, projectName, repositoryOwner, status, lastSyncAt, nextSyncAt,
                    consecutiveFailures, lastError, null, null, 0, 0);
        }
    }

    record EventEvidence(
            UUID eventId,
            UUID projectId,
            String projectName,
            String eventType,
            String title,
            String summary,
            String sourceUrl,
            String state,
            String riskLevel,
            int importance,
            Instant occurredAt) {
    }
}
