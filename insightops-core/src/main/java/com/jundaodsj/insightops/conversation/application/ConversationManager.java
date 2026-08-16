package com.jundaodsj.insightops.conversation.application;

import com.jundaodsj.insightops.identity.application.ActorContext;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationManager {

    List<ConversationSummary> list(ActorContext actor, boolean includeArchived);

    Optional<ConversationSummary> rename(
            ActorContext actor,
            UUID sessionId,
            String title,
            Instant now);

    Optional<ConversationSummary> archive(
            ActorContext actor,
            UUID sessionId,
            boolean archived,
            Instant now);

    boolean delete(ActorContext actor, UUID sessionId);

    record ConversationSummary(
            UUID id,
            String title,
            String status,
            int messageCount,
            Instant createdAt,
            Instant updatedAt) {
    }
}
