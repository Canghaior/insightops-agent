package com.jundaodsj.insightops.infrastructure.persistence;

import com.jundaodsj.insightops.agent.application.AgentCheckpointStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcAgentCheckpointStore implements AgentCheckpointStore {

    private final JdbcClient jdbcClient;

    public JdbcAgentCheckpointStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional
    public boolean requestPause(UUID workspaceId, UUID userId, UUID runId, Instant requestedAt) {
        return jdbcClient.sql("""
                        update agent_plan plan
                        set status = 'PAUSE_REQUESTED', pause_requested_at = :requestedAt
                        where plan.id = (
                            select candidate.id from agent_plan candidate
                            join agent_run run on run.id = candidate.run_id
                            where run.id = :runId and run.workspace_id = :workspaceId
                              and run.owner_user_id = :userId and run.status = 'RUNNING'
                            order by candidate.version desc limit 1
                        ) and plan.status = 'ACTIVE'
                        """)
                .param("requestedAt", timestamp(requestedAt))
                .param("runId", runId).param("workspaceId", workspaceId).param("userId", userId)
                .update() == 1;
    }

    @Override
    public ControlState control(UUID runId) {
        return jdbcClient.sql("select status from agent_plan where run_id = :runId order by version desc limit 1")
                .param("runId", runId).query(String.class).optional()
                .map(status -> switch (status) {
                    case "ACTIVE" -> ControlState.ACTIVE;
                    case "PAUSE_REQUESTED" -> ControlState.PAUSE_REQUESTED;
                    case "PAUSED" -> ControlState.PAUSED;
                    default -> ControlState.TERMINAL;
                }).orElse(ControlState.TERMINAL);
    }

    @Override
    @Transactional
    public Checkpoint save(CheckpointDraft draft) {
        jdbcClient.sql("select id from agent_plan where id = :id for update")
                .param("id", draft.planId()).query(UUID.class).single();
        int sequence = jdbcClient.sql("select coalesce(max(sequence), 0) + 1 from agent_plan_checkpoint where plan_id = :planId")
                .param("planId", draft.planId()).query(Integer.class).single();
        jdbcClient.sql("""
                        insert into agent_plan_checkpoint (
                            id, plan_id, run_id, workspace_id, user_id, sequence, reason,
                            status, state_json, budget_json, created_at
                        ) values (
                            :id, :planId, :runId, :workspaceId, :userId, :sequence, :reason,
                            'AVAILABLE', cast(:stateJson as jsonb), cast(:budgetJson as jsonb), :createdAt
                        )
                        """)
                .param("id", draft.id()).param("planId", draft.planId())
                .param("runId", draft.runId()).param("workspaceId", draft.workspaceId())
                .param("userId", draft.userId()).param("sequence", sequence)
                .param("reason", draft.reason()).param("stateJson", draft.stateJson())
                .param("budgetJson", draft.budgetJson()).param("createdAt", timestamp(draft.createdAt()))
                .update();
        return new Checkpoint(draft.id(), draft.planId(), draft.runId(), draft.workspaceId(),
                draft.userId(), sequence, draft.reason(), "AVAILABLE", draft.stateJson(),
                draft.budgetJson(), draft.createdAt(), null);
    }

    @Override
    public Optional<Checkpoint> findOwned(UUID checkpointId, UUID workspaceId, UUID userId) {
        return jdbcClient.sql("""
                        select id, plan_id, run_id, workspace_id, user_id, sequence, reason,
                               status, state_json::text, budget_json::text, created_at, resumed_run_id
                        from agent_plan_checkpoint
                        where id = :id and workspace_id = :workspaceId and user_id = :userId
                        """)
                .param("id", checkpointId).param("workspaceId", workspaceId).param("userId", userId)
                .query((rs, rowNum) -> new Checkpoint(
                        rs.getObject("id", UUID.class), rs.getObject("plan_id", UUID.class),
                        rs.getObject("run_id", UUID.class), rs.getObject("workspace_id", UUID.class),
                        rs.getObject("user_id", UUID.class), rs.getInt("sequence"),
                        rs.getString("reason"), rs.getString("status"), rs.getString("state_json"),
                        rs.getString("budget_json"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("resumed_run_id", UUID.class))).optional();
    }

    @Override
    public Optional<Checkpoint> findLatestForRun(
            UUID runId, UUID workspaceId, UUID userId) {
        return jdbcClient.sql("""
                        select id, plan_id, run_id, workspace_id, user_id, sequence, reason,
                               status, state_json::text, budget_json::text, created_at, resumed_run_id
                        from agent_plan_checkpoint
                        where run_id = :runId and workspace_id = :workspaceId
                          and user_id = :userId and status = 'AVAILABLE'
                        order by created_at desc, sequence desc limit 1
                        """)
                .param("runId", runId).param("workspaceId", workspaceId)
                .param("userId", userId)
                .query((rs, rowNum) -> new Checkpoint(
                        rs.getObject("id", UUID.class), rs.getObject("plan_id", UUID.class),
                        rs.getObject("run_id", UUID.class), rs.getObject("workspace_id", UUID.class),
                        rs.getObject("user_id", UUID.class), rs.getInt("sequence"),
                        rs.getString("reason"), rs.getString("status"), rs.getString("state_json"),
                        rs.getString("budget_json"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("resumed_run_id", UUID.class))).optional();
    }

    @Override
    public boolean consume(UUID checkpointId, UUID resumedRunId, Instant consumedAt) {
        return jdbcClient.sql("""
                        update agent_plan_checkpoint
                        set status = 'CONSUMED', consumed_at = :consumedAt, resumed_run_id = :resumedRunId
                        where id = :id and status = 'AVAILABLE'
                        """)
                .param("consumedAt", timestamp(consumedAt)).param("resumedRunId", resumedRunId)
                .param("id", checkpointId).update() == 1;
    }

    @Override
    public void linkResume(UUID planId, UUID checkpointId, Instant resumedAt) {
        jdbcClient.sql("""
                        update agent_plan set resumed_from_checkpoint_id = :checkpointId,
                            resumed_at = :resumedAt, execution_epoch = execution_epoch + 1
                        where id = :planId
                        """)
                .param("checkpointId", checkpointId).param("resumedAt", timestamp(resumedAt))
                .param("planId", planId).update();
    }

    @Override
    public void markPaused(UUID planId, UUID checkpointId, Instant pausedAt) {
        jdbcClient.sql("""
                        update agent_plan set status = 'PAUSED', paused_at = :pausedAt,
                            finished_at = :pausedAt where id = :planId
                            and status in ('ACTIVE', 'PAUSE_REQUESTED')
                        """)
                .param("pausedAt", timestamp(pausedAt)).param("planId", planId).update();
    }

    @Override
    public void recordRevision(UUID planId, int version, String reason, String graphJson, Instant createdAt) {
        jdbcClient.sql("""
                        insert into agent_plan_revision (id, plan_id, version, reason, graph_json, created_at)
                        values (:id, :planId, :version, :reason, cast(:graphJson as jsonb), :createdAt)
                        on conflict (plan_id, version) do nothing
                        """)
                .param("id", UUID.randomUUID()).param("planId", planId).param("version", version)
                .param("reason", reason).param("graphJson", graphJson)
                .param("createdAt", timestamp(createdAt)).update();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
