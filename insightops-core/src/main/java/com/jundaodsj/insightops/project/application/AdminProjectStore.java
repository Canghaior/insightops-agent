package com.jundaodsj.insightops.project.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminProjectStore {

    List<ManagedProject> list(UUID workspaceId);

    Optional<ManagedProject> find(UUID workspaceId, UUID projectId);

    ManagedProject create(
            UUID projectId,
            UUID workspaceId,
            String repositoryOwner,
            String repositoryName,
            String canonicalUrl,
            int priority,
            int syncIntervalHours,
            List<String> chatAliases,
            Instant now);

    Optional<ManagedProject> update(
            UUID workspaceId,
            UUID projectId,
            String repositoryOwner,
            String repositoryName,
            String canonicalUrl,
            int priority,
            int syncIntervalHours,
            List<String> chatAliases,
            Instant now);

    Optional<ManagedProject> setEnabled(
            UUID workspaceId,
            UUID projectId,
            boolean enabled,
            Instant now);

    DeleteResult deleteEmpty(UUID workspaceId, UUID projectId);

    enum DeleteResult {
        DELETED,
        NOT_FOUND,
        HAS_DEPENDENCIES
    }

    record ManagedProject(
            UUID projectId,
            String platform,
            String repositoryOwner,
            String repositoryName,
            String canonicalUrl,
            int priority,
            int syncIntervalHours,
            List<String> chatAliases,
            boolean enabled,
            String lastSyncStatus,
            Instant lastSyncAt,
            Instant nextSyncAt,
            int consecutiveFailures,
            String lastSyncError,
            long releaseCount,
            long knowledgeSourceCount,
            long watcherCount,
            long activeJobCount,
            Instant createdAt,
            Instant updatedAt) {

        public ManagedProject(UUID projectId, String platform, String repositoryOwner,
                              String repositoryName, String canonicalUrl, int priority,
                              boolean enabled, String lastSyncStatus, Instant lastSyncAt,
                              Instant nextSyncAt, int consecutiveFailures, String lastSyncError,
                              long releaseCount, long knowledgeSourceCount, long watcherCount,
                              long activeJobCount, Instant createdAt, Instant updatedAt) {
            this(projectId, platform, repositoryOwner, repositoryName, canonicalUrl,
                    priority, 6, List.of(), enabled, lastSyncStatus, lastSyncAt, nextSyncAt,
                    consecutiveFailures, lastSyncError, releaseCount, knowledgeSourceCount,
                    watcherCount, activeJobCount, createdAt, updatedAt);
        }

        public boolean hasCollectedData() {
            return releaseCount > 0 || knowledgeSourceCount > 0;
        }

        public boolean hasDependencies() {
            return hasCollectedData() || watcherCount > 0 || activeJobCount > 0;
        }
    }
}
