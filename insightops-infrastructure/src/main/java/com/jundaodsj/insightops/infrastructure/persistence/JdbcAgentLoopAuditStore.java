package com.jundaodsj.insightops.infrastructure.persistence;

import com.jundaodsj.insightops.agent.application.AgentLoopAuditStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Repository
public class JdbcAgentLoopAuditStore implements AgentLoopAuditStore {

    private final JdbcClient jdbcClient;

    public JdbcAgentLoopAuditStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional
    public void recordStep(
            UUID runId,
            UUID stepId,
            int stepNo,
            String stepType,
            String status,
            String inputPayload,
            String outputPayload,
            Instant startedAt,
            Instant finishedAt) {
        jdbcClient.sql("""
                        insert into agent_step
                            (id, run_id, step_no, step_type, status, input_payload,
                             output_payload, started_at, finished_at, created_at)
                        values
                            (:id, :runId, :stepNo, :stepType, :status,
                             cast(:inputPayload as jsonb), cast(:outputPayload as jsonb),
                             :startedAt, :finishedAt, :startedAt)
                        """)
                .param("id", stepId)
                .param("runId", runId)
                .param("stepNo", stepNo)
                .param("stepType", stepType)
                .param("status", status)
                .param("inputPayload", json(inputPayload))
                .param("outputPayload", json(outputPayload))
                .param("startedAt", timestamp(startedAt))
                .param("finishedAt", timestamp(finishedAt))
                .update();
    }

    private static String json(String value) {
        return value == null || value.isBlank() ? "{}" : value;
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
