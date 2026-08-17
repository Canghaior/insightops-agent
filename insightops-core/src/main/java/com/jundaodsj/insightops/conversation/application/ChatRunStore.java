package com.jundaodsj.insightops.conversation.application;

import com.jundaodsj.insightops.model.application.ModelUsage;
import com.jundaodsj.insightops.identity.application.ActorContext;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatRunStore {

    List<StoredMessage> recentMessages(ActorContext actor, UUID sessionId, int limit);

    Optional<SessionHistory> sessionHistory(ActorContext actor, UUID sessionId, int limit);

    boolean ownsRun(ActorContext actor, UUID runId);

    UUID startRun(
            ActorContext actor,
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

    default void succeedRunWithCitations(
            UUID runId,
            String answer,
            String provider,
            String model,
            ModelUsage usage,
            List<ChatCitation> citations,
            Instant finishedAt) {
        succeedRun(runId, answer, provider, model, usage,
                citations == null ? List.of() : citations.stream()
                        .map(ChatCitation::url).distinct().toList(), finishedAt);
    }

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
            List<String> citations,
            List<ChatCitation> citationDetails,
            int sequenceNo,
            Instant createdAt) {
        public HistoryMessage(UUID id, String role, String content, List<String> citations,
                              int sequenceNo, Instant createdAt) {
            this(id, role, content, citations, List.of(), sequenceNo, createdAt);
        }
    }
}
