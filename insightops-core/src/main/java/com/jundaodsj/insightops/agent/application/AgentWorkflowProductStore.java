package com.jundaodsj.insightops.agent.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** User presets, governed template shares and read-only workflow quality samples. */
public interface AgentWorkflowProductStore {

    List<ParameterPreset> presets(
            UUID workspaceId, UUID ownerUserId, UUID templateId, UUID versionId);

    ParameterPreset savePreset(PresetDraft draft);

    boolean deletePreset(UUID workspaceId, UUID ownerUserId, UUID presetId);

    TemplateShare createShare(ShareDraft draft);

    List<TemplateShare> shares(UUID workspaceId, UUID templateId);

    Optional<TemplateShare> findActiveShare(String tokenHash, Instant now);

    boolean revokeShare(UUID workspaceId, UUID shareId, Instant now);

    void recordImport(UUID shareId, Instant now);

    List<WorkflowRunMetric> runMetrics(
            UUID workspaceId, UUID templateId, Instant from, int limit);

    record PresetDraft(
            UUID id,
            UUID workspaceId,
            UUID ownerUserId,
            UUID templateId,
            UUID templateVersionId,
            String name,
            String valuesJson,
            Instant now) {
    }

    record ParameterPreset(
            UUID id,
            UUID workspaceId,
            UUID ownerUserId,
            UUID templateId,
            UUID templateVersionId,
            String name,
            String valuesJson,
            Instant createdAt,
            Instant updatedAt) {
    }

    record ShareDraft(
            UUID id,
            UUID sourceWorkspaceId,
            UUID templateId,
            UUID templateVersionId,
            String tokenHash,
            Instant expiresAt,
            UUID createdBy,
            Instant createdAt) {
    }

    record TemplateShare(
            UUID id,
            UUID sourceWorkspaceId,
            UUID templateId,
            UUID templateVersionId,
            String status,
            Instant expiresAt,
            UUID createdBy,
            Instant createdAt,
            Instant revokedAt,
            int importCount,
            Instant lastImportedAt) {
    }

    record WorkflowRunMetric(
            UUID runId,
            int templateVersion,
            String status,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            long totalTokens,
            BigDecimal estimatedCostCny,
            Boolean helpful,
            int feedbackCount,
            int helpfulCount,
            int citationCount,
            int correctCitationCount,
            int nodeCount,
            int successfulNodeCount) {
    }
}
