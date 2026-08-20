package com.jundaodsj.insightops.knowledge.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KnowledgeUploadStore {

    UploadRecord create(CreateUpload command, Instant now);

    List<UploadRecord> listVisible(UUID workspaceId, UUID userId, boolean systemAdmin);

    Optional<UploadRecord> findVisible(UUID workspaceId, UUID userId, boolean systemAdmin, UUID uploadId);

    Optional<DeleteTarget> delete(UUID workspaceId, UUID userId, boolean systemAdmin, UUID uploadId);

    long workspaceBytes(UUID workspaceId);

    record CreateUpload(
            UUID uploadId, UUID sourceId, UUID workspaceId, UUID projectId, UUID uploadedBy,
            String originalName, String storageKey, String mediaType, long byteSize,
            String sha256, String visibility, long workspaceQuotaBytes) { }

    record UploadRecord(
            UUID uploadId, UUID sourceId, UUID projectId, String projectName,
            UUID uploadedBy, String uploaderName, String originalName, String mediaType,
            long byteSize, String sha256, String visibility, String status,
            int pageCount, String errorMessage, String currentItem,
            Instant heartbeatAt, Instant leaseExpiresAt, Instant createdAt, Instant updatedAt) { }

    record DeleteTarget(String storageKey, UUID sourceId) { }
}
