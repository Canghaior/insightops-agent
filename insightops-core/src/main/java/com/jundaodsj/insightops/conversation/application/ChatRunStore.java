package com.jundaodsj.insightops.conversation.application;

import com.jundaodsj.insightops.model.application.ModelUsage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ChatRunStore {

    UUID startRun(
            UUID runId,
            UUID requestedSessionId,
            String traceId,
            String question,
            Instant startedAt);

    void succeedRun(
            UUID runId,
            String answer,
            String provider,
            String model,
            ModelUsage usage,
            List<String> citations,
            Instant finishedAt);

    void cancelRun(UUID runId, String partialAnswer, Instant finishedAt);

    void failRun(
            UUID runId,
            String partialAnswer,
            String failureCode,
            Instant finishedAt);
}
