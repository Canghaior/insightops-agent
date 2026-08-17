package com.jundaodsj.insightops.agent.application;

import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.conversation.application.ChatCitation;

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
            List<RunToolCall> toolCalls) {
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
            Instant finishedAt) {
    }
}
