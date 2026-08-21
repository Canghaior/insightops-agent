package com.jundaodsj.insightops.infrastructure.persistence;

import com.jundaodsj.insightops.agent.application.AgentConditionalGraphStore;
import com.jundaodsj.insightops.agent.application.AgentOrchestrationStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcAgentConditionalGraphStore implements AgentConditionalGraphStore {

    private final JdbcClient jdbcClient;

    public JdbcAgentConditionalGraphStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional
    public List<AgentOrchestrationStore.PlanNode> appendGraph(
            UUID planId, UUID runId, int round, int revision,
            List<GraphNodeDraft> nodes, Instant createdAt) {
        List<AgentOrchestrationStore.PlanNode> stored = new ArrayList<>();
        for (GraphNodeDraft node : nodes) {
            jdbcClient.sql("""
                            insert into agent_plan_node (
                                id, plan_id, run_id, provider_tool_call_id, plan_round, position,
                                tool_name, risk_level, required, status, input_payload,
                                condition_type, expected_error_codes, revision, created_at, updated_at
                            ) values (
                                :id, :planId, :runId, :providerId, :round, :position,
                                :toolName, :riskLevel, :required, 'PENDING', cast(:input as jsonb),
                                :conditionType, cast(:expectedErrors as jsonb), :revision,
                                :createdAt, :createdAt
                            )
                            """)
                    .param("id", node.id()).param("planId", planId).param("runId", runId)
                    .param("providerId", node.providerToolCallId()).param("round", round)
                    .param("position", node.position()).param("toolName", node.toolName())
                    .param("riskLevel", node.riskLevel()).param("required", node.required())
                    .param("input", node.inputPayload()).param("conditionType", node.conditionType())
                    .param("expectedErrors", node.expectedErrorCodesJson()).param("revision", revision)
                    .param("createdAt", timestamp(createdAt)).update();
            for (UUID dependencyId : node.dependencyIds()) {
                jdbcClient.sql("""
                                insert into agent_plan_dependency (node_id, depends_on_node_id)
                                values (:nodeId, :dependencyId) on conflict do nothing
                                """)
                        .param("nodeId", node.id()).param("dependencyId", dependencyId).update();
            }
            stored.add(new AgentOrchestrationStore.PlanNode(
                    node.id(), round, node.position(), node.toolName(), "PENDING",
                    List.copyOf(node.dependencyIds())));
        }
        return List.copyOf(stored);
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
