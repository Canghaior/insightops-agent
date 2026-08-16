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
}
