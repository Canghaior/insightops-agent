package com.jundaodsj.insightops.agent.application;

import com.jundaodsj.insightops.identity.application.ActorContext;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AgentCheckpointQuery {
    Optional<CheckpointSummary> latest(ActorContext actor, UUID runId);

    record CheckpointSummary(
            UUID id,
            UUID runId,
            int sequence,
            String reason,
            String status,
            Instant createdAt,
            UUID resumedRunId) { }
}
