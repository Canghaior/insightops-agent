package com.jundaodsj.insightops.agent.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Workspace-owned, immutable-version workflow templates for governed Agent plans. */
public interface AgentWorkflowTemplateStore {

    List<WorkflowTemplate> overview(UUID workspaceId);

    Optional<WorkflowTemplate> find(UUID workspaceId, UUID templateId);

    WorkflowTemplate create(
            UUID workspaceId, UUID userId, TemplateDraft draft, Instant now);

    WorkflowTemplate createVersion(
            UUID workspaceId, UUID templateId, UUID userId, VersionDraft draft, Instant now);

    WorkflowTemplate activate(
            UUID workspaceId, UUID templateId, UUID versionId, UUID userId,
            String reason, Instant now);

    record TemplateDraft(
            String name,
            String description,
            String category,
            VersionDraft version) {
    }

    record VersionDraft(
            String summary,
            String entryQuestion,
            String graphSpecJson) {
    }

    record WorkflowTemplate(
            UUID id,
            UUID workspaceId,
            String name,
            String description,
            String category,
            String status,
            UUID activeVersionId,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt,
            List<WorkflowVersion> versions) {
    }

    record WorkflowVersion(
            UUID id,
            UUID templateId,
            int version,
            String status,
            String summary,
            String entryQuestion,
            String graphSpecJson,
            UUID createdBy,
            Instant createdAt,
            Instant activatedAt) {
    }
}
