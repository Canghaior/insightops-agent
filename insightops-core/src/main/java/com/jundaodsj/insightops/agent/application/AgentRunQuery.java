package com.jundaodsj.insightops.agent.application;

import com.jundaodsj.insightops.conversation.application.ChatCitation;
import com.jundaodsj.insightops.identity.application.ActorContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentRunQuery {

    RunPage listRuns(ActorContext actor, int page, int size, String status);

    Optional<RunDetail> findRun(ActorContext actor, UUID runId);

    record RunPage(
            List<RunSummary> items,
            long total,
            int page,
            int size,
            int totalPages) {
    }

    record RunSummary(
            UUID id,
            UUID sessionId,
            String traceId,
            String status,
            String question,
            String modelProvider,
            String modelName,
            int toolRounds,
            Integer promptTokens,
            Integer completionTokens,
            Long durationMs,
            Instant createdAt,
            Instant finishedAt) {
    }

    record RunDetail(
            UUID id,
            UUID sessionId,
            String traceId,
            String status,
            String question,
            String answer,
            String modelProvider,
            String modelName,
            int toolRounds,
            Integer promptTokens,
            Integer completionTokens,
            BigDecimal estimatedCostCny,
            LocalDate pricingEffectiveDate,
            String failureCode,
            String failureMessage,
            Long durationMs,
            Instant startedAt,
            Instant finishedAt,
            Instant createdAt,
            List<String> sources,
            List<ChatCitation> citationDetails,
            List<RunStep> steps,
            List<RunToolCall> toolCalls,
            RunPlan plan,
            RunBudget budget) {
    }

    record RunStep(
            UUID id,
            int stepNo,
            String stepType,
            String status,
            Object inputPayload,
            Object outputPayload,
            Long durationMs,
            Instant startedAt,
            Instant finishedAt) {
    }

    record RunToolCall(
            UUID id,
            UUID stepId,
            String toolName,
            String status,
            Object requestPayload,
            Object resultPayload,
            String errorMessage,
            Long durationMs,
            Instant createdAt,
            Instant finishedAt,
            List<RunToolAttempt> attempts) {
    }

    record RunToolAttempt(
            UUID id,
            int attemptNo,
            String status,
            String errorCode,
            boolean retryable,
            long retryDelayMs,
            Long durationMs,
            Instant startedAt,
            Instant finishedAt) {
    }

    record RunPlan(
            UUID id,
            int version,
            String status,
            int maxParallelism,
            Instant createdAt,
            Instant finishedAt,
            List<RunPlanNode> nodes) {
    }

    record RunPlanNode(
            UUID id,
            int round,
            int position,
            String toolName,
            String riskLevel,
            boolean required,
            String status,
            UUID toolCallId,
            String errorCode,
            List<UUID> dependencyIds,
            Instant startedAt,
            Instant finishedAt,
            String conditionType,
            List<String> expectedErrorCodes,
            int revision) {

        public RunPlanNode(
                UUID id, int round, int position, String toolName, String riskLevel,
                boolean required, String status, UUID toolCallId, String errorCode,
                List<UUID> dependencyIds, Instant startedAt, Instant finishedAt) {
            this(id, round, position, toolName, riskLevel, required, status, toolCallId,
                    errorCode, dependencyIds, startedAt, finishedAt,
                    "ALL_TERMINAL", List.of(), 1);
        }
    }

    record RunBudget(
            int maxNodes,
            int maxParallelism,
            int maxToolAttempts,
            long maxModelTokens,
            BigDecimal maxEstimatedCostCny,
            int usedNodes,
            int usedToolAttempts,
            long usedModelTokens,
            BigDecimal estimatedCostCny,
            String status,
            String exhaustionReason,
            Instant updatedAt) {
    }
}
