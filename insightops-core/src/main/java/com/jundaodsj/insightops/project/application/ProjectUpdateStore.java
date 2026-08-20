package com.jundaodsj.insightops.project.application;

import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.tool.application.github.GitHubRelease;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ProjectUpdateStore {

    List<TrackedProject> claimDueProjects(Instant now, Duration lockDuration, int limit);

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
            int consecutiveFailures) {
        public TrackedProject(UUID id, UUID workspaceId, String catalogProjectId,
                              String owner, String repository, int consecutiveFailures) {
            this(id, workspaceId, catalogProjectId, owner, repository, 6, consecutiveFailures);
        }
    }

    record SyncResult(int releaseCount, int newEventCount) {
    }

    record ProjectUpdate(
            UUID eventId,
            UUID projectId,
            String projectName,
            String repositoryOwner,
            String versionTag,
            String title,
            String summary,
            String sourceUrl,
            boolean prerelease,
            Instant occurredAt,
            Instant collectedAt,
            boolean read,
            UUID analysisId,
            String analysisStatus,
            String riskLevel,
            String recommendation,
            String intelligenceSummary) {
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
            String lastError) {
    }
}
