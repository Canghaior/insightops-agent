package com.jundaodsj.insightops.conversation.application;

import com.jundaodsj.insightops.model.application.ModelUsage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatRunStore {

    List<StoredMessage> recentMessages(UUID sessionId, int limit);

    Optional<SessionHistory> sessionHistory(UUID sessionId, int limit);

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

    record StoredMessage(String role, String content) {
    }

    record SessionHistory(
            UUID sessionId,
            String title,
            List<HistoryMessage> messages,
            boolean hasEarlierMessages) {
    }

    record HistoryMessage(
            UUID id,
            String role,
            String content,
            int sequenceNo,
            Instant createdAt) {
    }
}
