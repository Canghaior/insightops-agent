package com.jundaodsj.insightops.infrastructure.persistence;

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
public class JdbcAgentOrchestrationStore implements AgentOrchestrationStore {

    private final JdbcClient jdbcClient;

    public JdbcAgentOrchestrationStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional
    public PlanHandle startRun(UUID runId, RunLimits limits, Instant startedAt) {
        int version = jdbcClient.sql("""
                        select coalesce(max(version), 0) + 1
                        from agent_plan where run_id = :runId
                        """)
                .param("runId", runId)
                .query(Integer.class)
                .single();
        UUID planId = UUID.randomUUID();
        jdbcClient.sql("""
                        insert into agent_plan
                            (id, run_id, version, status, max_parallelism, created_at)
                        values (:id, :runId, :version, 'ACTIVE', :maxParallelism, :createdAt)
                        """)
                .param("id", planId)
                .param("runId", runId)
                .param("version", version)
                .param("maxParallelism", limits.maxParallelism())
                .param("createdAt", timestamp(startedAt))
                .update();
        jdbcClient.sql("""
                        insert into agent_run_budget
                            (run_id, max_nodes, max_parallelism, max_tool_attempts,
                             max_model_tokens, max_estimated_cost_cny, status,
                             created_at, updated_at)
                        values
                            (:runId, :maxNodes, :maxParallelism, :maxToolAttempts,
                             :maxModelTokens, :maxEstimatedCostCny, 'ACTIVE',
                             :createdAt, :createdAt)
                        on conflict (run_id) do update set
                            max_nodes = excluded.max_nodes,
                            max_parallelism = excluded.max_parallelism,
                            max_tool_attempts = excluded.max_tool_attempts,
                            max_model_tokens = excluded.max_model_tokens,
                            max_estimated_cost_cny = excluded.max_estimated_cost_cny,
                            status = 'ACTIVE', exhaustion_reason = null,
                            updated_at = excluded.updated_at
                        """)
                .param("runId", runId)
                .param("maxNodes", limits.maxNodes())
                .param("maxParallelism", limits.maxParallelism())
                .param("maxToolAttempts", limits.maxToolAttempts())
                .param("maxModelTokens", limits.maxModelTokens())
                .param("maxEstimatedCostCny", limits.maxEstimatedCostCny())
                .param("createdAt", timestamp(startedAt))
                .update();
        return new PlanHandle(planId, version);
    }

    @Override
    @Transactional
    public List<PlanNode> appendLayer(
            UUID planId,
            UUID runId,
            int round,
            List<NodeDraft> nodes,
            List<UUID> dependencyIds,
            Instant createdAt) {
        List<PlanNode> result = new ArrayList<>();
        for (NodeDraft node : nodes) {
            jdbcClient.sql("""
                            insert into agent_plan_node
                                (id, plan_id, run_id, provider_tool_call_id, plan_round,
                                 position, tool_name, risk_level, required, status,
                                 input_payload, created_at, updated_at)
                            values
                                (:id, :planId, :runId, :providerToolCallId, :round,
                                 :position, :toolName, :riskLevel, :required, 'PENDING',
                                 cast(:inputPayload as jsonb), :createdAt, :createdAt)
                            """)
                    .param("id", node.id())
                    .param("planId", planId)
                    .param("runId", runId)
                    .param("providerToolCallId", node.providerToolCallId())
                    .param("round", round)
                    .param("position", node.position())
                    .param("toolName", node.toolName())
                    .param("riskLevel", node.riskLevel())
                    .param("required", node.required())
                    .param("inputPayload", json(node.inputPayload()))
                    .param("createdAt", timestamp(createdAt))
                    .update();
            for (UUID dependencyId : dependencyIds) {
                jdbcClient.sql("""
                                insert into agent_plan_dependency (node_id, depends_on_node_id)
                                values (:nodeId, :dependencyId)
                                """)
                        .param("nodeId", node.id())
                        .param("dependencyId", dependencyId)
                        .update();
            }
            result.add(new PlanNode(
                    node.id(), round, node.position(), node.toolName(), "PENDING",
                    List.copyOf(dependencyIds)));
        }
        return List.copyOf(result);
    }

    @Override
    public void updateNode(
            UUID nodeId,
            String status,
            UUID toolCallId,
            String errorCode,
            Instant updatedAt) {
        boolean running = "RUNNING".equals(status);
        boolean terminal = !running && !"PENDING".equals(status);
        jdbcClient.sql("""
                        update agent_plan_node set
                            status = :status,
                            tool_call_id = coalesce(:toolCallId, tool_call_id),
                            error_code = :errorCode,
                            started_at = case when :running then coalesce(started_at, :updatedAt)
                                              else started_at end,
                            finished_at = case when :terminal then :updatedAt else finished_at end,
                            updated_at = :updatedAt
                        where id = :id
                        """)
                .param("status", status)
                .param("toolCallId", toolCallId)
                .param("errorCode", errorCode)
                .param("running", running)
                .param("terminal", terminal)
                .param("updatedAt", timestamp(updatedAt))
                .param("id", nodeId)
                .update();
    }

    @Override
    public void updateBudget(UUID runId, BudgetSnapshot budget, Instant updatedAt) {
        jdbcClient.sql("""
                        update agent_run_budget set
                            used_nodes = :usedNodes,
                            used_tool_attempts = :usedToolAttempts,
                            used_model_tokens = :usedModelTokens,
                            estimated_cost_cny = :estimatedCostCny,
                            status = :status,
                            exhaustion_reason = :exhaustionReason,
                            updated_at = :updatedAt
                        where run_id = :runId
                        """)
                .param("usedNodes", budget.usedNodes())
                .param("usedToolAttempts", budget.usedToolAttempts())
                .param("usedModelTokens", budget.usedModelTokens())
                .param("estimatedCostCny", budget.estimatedCostCny())
                .param("status", budget.status())
                .param("exhaustionReason", budget.exhaustionReason())
                .param("updatedAt", timestamp(updatedAt))
                .param("runId", runId)
                .update();
    }

    @Override
    public void finishPlan(UUID planId, String status, Instant finishedAt) {
        jdbcClient.sql("""
                        update agent_plan
                        set status = :status, finished_at = :finishedAt
                        where id = :id
                        """)
                .param("status", status)
                .param("finishedAt", timestamp(finishedAt))
                .param("id", planId)
                .update();
    }

    private static String json(String value) {
        return value == null || value.isBlank() ? "{}" : value;
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
