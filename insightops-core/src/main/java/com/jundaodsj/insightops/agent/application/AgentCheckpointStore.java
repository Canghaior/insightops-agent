package com.jundaodsj.insightops.agent.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Durable safe-point control and cross-run resume state for Agent plans. */
public interface AgentCheckpointStore {

    boolean requestPause(UUID workspaceId, UUID userId, UUID runId, Instant requestedAt);

    ControlState control(UUID runId);

    Checkpoint save(CheckpointDraft draft);

    Optional<Checkpoint> findOwned(UUID checkpointId, UUID workspaceId, UUID userId);

    boolean consume(UUID checkpointId, UUID resumedRunId, Instant consumedAt);

    void linkResume(UUID planId, UUID checkpointId, Instant resumedAt);

    void markPaused(UUID planId, UUID checkpointId, Instant pausedAt);

    void recordRevision(UUID planId, int version, String reason, String graphJson, Instant createdAt);

    enum ControlState {
        ACTIVE,
        PAUSE_REQUESTED,
        PAUSED,
        TERMINAL
    }

    record CheckpointDraft(
            UUID id,
            UUID planId,
            UUID runId,
            UUID workspaceId,
            UUID userId,
            String reason,
            String stateJson,
            String budgetJson,
            Instant createdAt) {
    }

    record Checkpoint(
            UUID id,
            UUID planId,
            UUID sourceRunId,
            UUID workspaceId,
            UUID userId,
            int sequence,
            String reason,
            String status,
            String stateJson,
            String budgetJson,
            Instant createdAt,
            UUID resumedRunId) {
    }
}
