package com.jundaodsj.insightops.agent.application;

import java.time.Instant;
import java.util.UUID;

public interface AgentLoopAuditStore {

    void recordStep(
            UUID runId,
            UUID stepId,
            int stepNo,
            String stepType,
            String status,
            String inputPayload,
            String outputPayload,
            Instant startedAt,
            Instant finishedAt);
}
