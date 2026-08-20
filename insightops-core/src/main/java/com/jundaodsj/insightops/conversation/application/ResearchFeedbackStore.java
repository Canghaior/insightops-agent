package com.jundaodsj.insightops.conversation.application;

import com.jundaodsj.insightops.identity.application.ActorContext;

import java.time.Instant;
import java.util.UUID;

public interface ResearchFeedbackStore {

    boolean saveAnswerFeedback(
            ActorContext actor, UUID runId, Boolean helpful, String reason, String comment, Instant now);

    boolean saveCitationFeedback(
            ActorContext actor, UUID runId, String citationUrl, boolean correct, String comment, Instant now);
}
