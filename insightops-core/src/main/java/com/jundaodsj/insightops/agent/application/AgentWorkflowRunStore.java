package com.jundaodsj.insightops.agent.application;

import com.jundaodsj.insightops.conversation.application.ChatCitation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Immutable template snapshots plus lease-fenced runtime node state for workflow Agent runs. */
public interface AgentWorkflowRunStore {

    Optional<WorkflowRun> find(UUID runId);

    Optional<WorkflowRun> findByRequest(
            UUID workspaceId, UUID ownerUserId, UUID requestId);

    void create(WorkflowRunDraft draft, List<NodeDraft> nodes);

    NodeAttempt beginNode(
            UUID runId, String logicalNodeId, UUID leaseToken, int runAttempt,
            String workerId, String resolvedInputJson, Instant now);

    void finishNode(
            UUID runId, String logicalNodeId, UUID leaseToken, UUID attemptId,
            String status, UUID toolCallId, UUID planNodeId, String outputJson,
            String exposedOutputJson, String promptAppendix, String sourceUrlsJson,
            String citationsJson, long inputTokens, long outputTokens,
            BigDecimal estimatedCostCny, String errorCode, Instant now);

    void markReused(UUID runId, String logicalNodeId, UUID planNodeId, Instant now);

    record WorkflowRunDraft(
            UUID runId,
            UUID workspaceId,
            UUID ownerUserId,
            UUID templateId,
            UUID templateVersionId,
            String templateName,
            int templateVersion,
            String entryQuestion,
            String graphSpecJson,
            String inputJson,
            String toolContractFingerprint,
            UUID requestId,
            UUID sourceRunId,
            UUID retryRootRunId,
            String retryFromNodeId,
            Instant createdAt) {
    }

    record NodeDraft(
            UUID id,
            String logicalNodeId,
            String toolName,
            int toolVersion,
            String riskLevel,
            boolean required,
            String conditionType,
            String dependencyNodeIdsJson,
            String argumentTemplateJson,
            String exposeOutputsJson,
            String status,
            UUID reusedFromNodeId,
            String resolvedInputJson,
            String outputJson,
            String exposedOutputJson,
            String promptAppendix,
            String sourceUrlsJson,
            String citationsJson,
            int attemptCount,
            long inputTokens,
            long outputTokens,
            BigDecimal estimatedCostCny,
            String errorCode,
            Instant startedAt,
            Instant finishedAt,
            Instant createdAt) {
    }

    record WorkflowRun(
            UUID runId,
            UUID workspaceId,
            UUID ownerUserId,
            UUID templateId,
            UUID templateVersionId,
            String templateName,
            int templateVersion,
            String entryQuestion,
            String graphSpecJson,
            String inputJson,
            String toolContractFingerprint,
            UUID requestId,
            UUID sourceRunId,
            UUID retryRootRunId,
            String retryFromNodeId,
            Instant createdAt,
            List<WorkflowNode> nodes) {
    }

    record WorkflowNode(
            UUID id,
            String logicalNodeId,
            String toolName,
            int toolVersion,
            String riskLevel,
            boolean required,
            String conditionType,
            String dependencyNodeIdsJson,
            String argumentTemplateJson,
            String exposeOutputsJson,
            String resolvedInputJson,
            String outputJson,
            String exposedOutputJson,
            String promptAppendix,
            String sourceUrlsJson,
            String citationsJson,
            String status,
            int attemptCount,
            UUID toolCallId,
            UUID planNodeId,
            UUID reusedFromNodeId,
            long inputTokens,
            long outputTokens,
            BigDecimal estimatedCostCny,
            String errorCode,
            Instant startedAt,
            Instant finishedAt,
            Instant createdAt,
            Instant updatedAt,
            List<NodeAttemptView> attempts) {
        public boolean reusable() {
            return "SUCCEEDED".equals(status) || "REUSED".equals(status);
        }
    }

    record NodeAttempt(
            UUID id, UUID workflowNodeId, int attemptNo, Instant startedAt) {
    }

    record NodeAttemptView(
            UUID id,
            int attemptNo,
            int runAttempt,
            String workerId,
            UUID toolCallId,
            String status,
            String resolvedInputJson,
            String outputJson,
            long inputTokens,
            long outputTokens,
            BigDecimal estimatedCostCny,
            String errorCode,
            Instant startedAt,
            Instant finishedAt) {
    }
}
