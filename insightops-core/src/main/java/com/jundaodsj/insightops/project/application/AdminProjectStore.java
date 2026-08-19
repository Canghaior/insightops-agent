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
            Instant now);

    Optional<ManagedProject> update(
            UUID workspaceId,
            UUID projectId,
            String repositoryOwner,
            String repositoryName,
            String canonicalUrl,
            int priority,
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
            boolean enabled,
            String lastSyncStatus,
            Instant lastSyncAt,
            Instant nextSyncAt,
            int consecutiveFailures,
            String lastSyncError,
            long releaseCount,
            long knowledgeSourceCount,
            long watcherCount,
            long jobCount,
            Instant createdAt,
            Instant updatedAt) {

        public boolean hasCollectedData() {
            return releaseCount > 0 || knowledgeSourceCount > 0;
        }

        public boolean hasDependencies() {
            return hasCollectedData() || watcherCount > 0 || jobCount > 0;
        }
    }
}
