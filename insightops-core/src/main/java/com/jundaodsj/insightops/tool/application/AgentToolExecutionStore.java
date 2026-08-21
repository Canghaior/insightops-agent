package com.jundaodsj.insightops.tool.application;

import java.time.Instant;
import java.util.UUID;

public interface AgentToolExecutionStore {

    void startTool(
            UUID runId,
            UUID stepId,
            UUID toolCallId,
            int stepNo,
            String toolName,
            String idempotencyKey,
            String requestPayload,
            Instant startedAt);

    void succeedTool(
            UUID runId,
            UUID stepId,
            UUID toolCallId,
            String resultPayload,
            long durationMs,
            Instant finishedAt);

    void failTool(
            UUID stepId,
            UUID toolCallId,
            String errorCode,
            long durationMs,
            Instant finishedAt);

    default void finishTool(
            UUID stepId,
            UUID toolCallId,
            String status,
            String errorCode,
            long durationMs,
            Instant finishedAt) {
        failTool(stepId, toolCallId, errorCode, durationMs, finishedAt);
    }

    default void startAttempt(
            UUID attemptId,
            UUID toolCallId,
            int attemptNo,
            Instant startedAt) {
    }

    default void finishAttempt(
            UUID attemptId,
            String status,
            String errorCode,
            boolean retryable,
            long retryDelayMs,
            long durationMs,
            Instant finishedAt) {
    }
}
