package com.jundaodsj.insightops.infrastructure.persistence;

import com.jundaodsj.insightops.agent.application.AgentCheckpointQuery;
import com.jundaodsj.insightops.identity.application.ActorContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcAgentCheckpointQuery implements AgentCheckpointQuery {
    private final JdbcClient jdbcClient;

    public JdbcAgentCheckpointQuery(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<CheckpointSummary> latest(ActorContext actor, UUID runId) {
        return jdbcClient.sql("""
                        select checkpoint.id, checkpoint.run_id, checkpoint.sequence,
                               checkpoint.reason, checkpoint.status, checkpoint.created_at,
                               checkpoint.resumed_run_id
                        from agent_plan_checkpoint checkpoint
                        join agent_run run on run.id = checkpoint.run_id
                        where checkpoint.run_id = :runId
                          and run.workspace_id = :workspaceId
                          and run.owner_user_id = :userId
                        order by checkpoint.sequence desc
                        limit 1
                        """)
                .param("runId", runId).param("workspaceId", actor.workspaceId())
                .param("userId", actor.userId())
                .query((rs, rowNum) -> new CheckpointSummary(
                        rs.getObject("id", UUID.class), rs.getObject("run_id", UUID.class),
                        rs.getInt("sequence"), rs.getString("reason"), rs.getString("status"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("resumed_run_id", UUID.class))).optional();
    }
}
