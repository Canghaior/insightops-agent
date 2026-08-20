package com.jundaodsj.insightops.knowledge.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KnowledgeStore {

    List<SourceTask> claimDueSources(Instant now, Duration lockDuration, int limit);

    void updateCollectionProgress(SourceTask source, CollectionProgress progress,
                                  Instant heartbeatAt, Duration lockDuration);

    SyncResult completeSuccessfulSync(SourceTask source, List<DocumentPage> pages,
                                      Instant completedAt, Instant nextSyncAt);

    void completeFailedSync(SourceTask source, String errorCode, String errorMessage,
                            Instant failedAt, Instant nextRetryAt);

    List<SourceStatus> sourceStatus(UUID workspaceId);

    boolean requestSync(UUID workspaceId, UUID sourceId, Instant now);

    SourceStatus createSource(SourceDefinition source, Instant now);

    Optional<SourceStatus> updateSource(UUID workspaceId, UUID sourceId,
                                        SourceDefinition source, Instant now);

    Optional<SourceStatus> setSourceEnabled(UUID workspaceId, UUID sourceId,
                                            boolean enabled, Instant now);

    DeleteResult deleteEmptySource(UUID workspaceId, UUID sourceId);

    record SourceTask(
            UUID jobId, UUID sourceId, UUID workspaceId, UUID projectId,
            String projectName, String sourceKey, String name, String sourceType,
            String rootUrl, String discoveryUrl, String allowedHost,
            String allowedPathPrefix, String trustTier, int syncIntervalHours,
            int consecutiveFailures, String fetchEtag, String fetchLastModified,
            String uploadStorageKey, String uploadOriginalName, String uploadMediaType,
            String uploadVisibility, UUID uploadUserId) {
        public SourceTask(UUID jobId, UUID sourceId, UUID workspaceId, UUID projectId,
                          String projectName, String sourceKey, String name, String sourceType,
                          String rootUrl, String discoveryUrl, String allowedHost,
                          String allowedPathPrefix, String trustTier, int syncIntervalHours,
                          int consecutiveFailures) {
            this(jobId, sourceId, workspaceId, projectId, projectName, sourceKey, name,
                    sourceType, rootUrl, discoveryUrl, allowedHost, allowedPathPrefix,
                    trustTier, syncIntervalHours, consecutiveFailures, null, null,
                    null, null, null, null, null);
        }

        public SourceTask(UUID jobId, UUID sourceId, UUID workspaceId, UUID projectId,
                          String projectName, String sourceKey, String name, String sourceType,
                          String rootUrl, String discoveryUrl, String allowedHost,
                          String allowedPathPrefix, String trustTier, int consecutiveFailures) {
            this(jobId, sourceId, workspaceId, projectId, projectName, sourceKey, name,
                    sourceType, rootUrl, discoveryUrl, allowedHost, allowedPathPrefix,
                    trustTier, 24, consecutiveFailures, null, null,
                    null, null, null, null, null);
        }
    }

    record SourceDefinition(
            UUID sourceId, UUID workspaceId, UUID projectId, String sourceKey,
            String name, String sourceType, String rootUrl, String discoveryUrl,
            String allowedHost, String allowedPathPrefix, String trustTier,
            int syncIntervalHours) { }

    record DocumentPage(
            String canonicalUrl, String title, String language, String versionLabel,
            String contentSha256, String contentText, String etag, String lastModified,
            List<DocumentChunk> chunks) {
    }

    record DocumentChunk(
            int index, String headingPath, String content, String contentSha256,
            int characterCount, int estimatedTokens) {
    }

    record SyncResult(
            int pageCount, int newDocuments, int changedDocuments,
            int unchangedDocuments, int chunkCount) {
    }

    record CollectionProgress(
            int maxPageCount, int discoveredUrlCount, int visitedUrlCount,
            int collectedPageCount, String currentUrl) {
    }

    record SourceStatus(
            UUID sourceId, UUID projectId, String projectName, String sourceKey,
            String name, String sourceType, String rootUrl, String discoveryUrl,
            String allowedHost, String allowedPathPrefix, String trustTier,
            int syncIntervalHours,
            boolean enabled, String status, Instant lastSyncAt, Instant nextSyncAt,
            int consecutiveFailures, String lastError, long documentCount,
            long revisionCount, long chunkCount, Instant lockedUntil, JobStatus lastJob) {
    }

    record JobStatus(
            UUID jobId, String status, int pageCount, int newDocumentCount,
            int changedDocumentCount, int unchangedDocumentCount, int chunkCount,
            int maxPageCount, int discoveredUrlCount, int visitedUrlCount,
            String currentUrl, Instant heartbeatAt, Instant leaseExpiresAt,
            String errorCode, String errorMessage, Instant startedAt, Instant finishedAt) {
    }

    enum DeleteResult {
        DELETED,
        NOT_FOUND,
        HAS_DEPENDENCIES
    }
}
