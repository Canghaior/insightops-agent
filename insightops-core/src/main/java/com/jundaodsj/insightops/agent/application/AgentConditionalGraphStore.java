package com.jundaodsj.insightops.agent.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Persists arbitrary validated graph nodes and their explicit dependency edges. */
public interface AgentConditionalGraphStore {

    List<AgentOrchestrationStore.PlanNode> appendGraph(
            UUID planId, UUID runId, int round, int revision,
            List<GraphNodeDraft> nodes, Instant createdAt);

    record GraphNodeDraft(
            UUID id,
            String providerToolCallId,
            int position,
            String toolName,
            String riskLevel,
            boolean required,
            String inputPayload,
            List<UUID> dependencyIds,
            String conditionType,
            String expectedErrorCodesJson) { }
}
