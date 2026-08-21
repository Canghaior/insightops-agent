package com.jundaodsj.insightops.infrastructure.persistence;

import com.jundaodsj.insightops.tool.application.AgentToolExecutionStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Repository
public class JdbcAgentToolExecutionStore implements AgentToolExecutionStore {

    private final JdbcClient jdbcClient;

    public JdbcAgentToolExecutionStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional
    public void startTool(
            UUID runId,
            UUID stepId,
            UUID toolCallId,
            int stepNo,
            String toolName,
            String idempotencyKey,
            String requestPayload,
            Instant startedAt) {
        jdbcClient.sql("""
                        insert into agent_step
                            (id, run_id, step_no, step_type, status, input_payload,
                             started_at, created_at)
                        values
                            (:id, :runId, :stepNo, 'TOOL', 'RUNNING',
                             cast(:inputPayload as jsonb), :startedAt, :startedAt)
                        """)
                .param("id", stepId)
                .param("runId", runId)
                .param("stepNo", stepNo)
                .param("inputPayload", requestPayload)
                .param("startedAt", timestamp(startedAt))
                .update();
        jdbcClient.sql("""
                        insert into tool_call
                            (id, run_id, step_id, tool_name, status, idempotency_key,
                             request_payload, created_at)
                        values
                            (:id, :runId, :stepId, :toolName, 'RUNNING', :idempotencyKey,
                             cast(:requestPayload as jsonb), :createdAt)
                        """)
                .param("id", toolCallId)
                .param("runId", runId)
                .param("stepId", stepId)
                .param("toolName", toolName)
                .param("idempotencyKey", idempotencyKey)
                .param("requestPayload", requestPayload)
                .param("createdAt", timestamp(startedAt))
                .update();
    }

    @Override
    @Transactional
    public void succeedTool(
            UUID runId,
            UUID stepId,
            UUID toolCallId,
            String resultPayload,
            long durationMs,
            Instant finishedAt) {
        jdbcClient.sql("""
                        update tool_call
                        set status = 'SUCCEEDED',
                            result_payload = cast(:resultPayload as jsonb),
                            duration_ms = :durationMs,
                            finished_at = :finishedAt
                        where id = :id and status = 'RUNNING'
                        """)
                .param("id", toolCallId)
                .param("resultPayload", resultPayload)
                .param("durationMs", durationMs)
                .param("finishedAt", timestamp(finishedAt))
                .update();
        jdbcClient.sql("""
                        update agent_step
                        set status = 'SUCCEEDED',
                            output_payload = cast(:outputPayload as jsonb),
                            finished_at = :finishedAt
                        where id = :id and status = 'RUNNING'
                        """)
                .param("id", stepId)
                .param("outputPayload", resultPayload)
                .param("finishedAt", timestamp(finishedAt))
                .update();
        jdbcClient.sql("update agent_run set tool_rounds = tool_rounds + 1 where id = :id")
                .param("id", runId)
                .update();
    }

    @Override
    @Transactional
    public void failTool(
            UUID stepId,
            UUID toolCallId,
            String errorCode,
            long durationMs,
            Instant finishedAt) {
        jdbcClient.sql("""
                        update tool_call
                        set status = 'FAILED',
                            error_message = :errorCode,
                            duration_ms = :durationMs,
                            finished_at = :finishedAt
                        where id = :id and status = 'RUNNING'
                        """)
                .param("id", toolCallId)
                .param("errorCode", errorCode)
                .param("durationMs", durationMs)
                .param("finishedAt", timestamp(finishedAt))
                .update();
        jdbcClient.sql("""
                        update agent_step
                        set status = 'FAILED', finished_at = :finishedAt
                        where id = :id and status = 'RUNNING'
                        """)
                .param("id", stepId)
                .param("finishedAt", timestamp(finishedAt))
                .update();
    }
    @Override
    @Transactional
    public void finishTool(
            UUID stepId,
            UUID toolCallId,
            String status,
            String errorCode,
            long durationMs,
            Instant finishedAt) {
        jdbcClient.sql("""
                        update tool_call
                        set status = :status,
                            error_message = :errorCode,
                            duration_ms = :durationMs,
                            finished_at = :finishedAt
                        where id = :id and status = 'RUNNING'
                        """)
                .param("id", toolCallId)
                .param("status", status)
                .param("errorCode", errorCode)
                .param("durationMs", durationMs)
                .param("finishedAt", timestamp(finishedAt))
                .update();
        jdbcClient.sql("""
                        update agent_step
                        set status = :status, finished_at = :finishedAt
                        where id = :id and status = 'RUNNING'
                        """)
                .param("id", stepId)
                .param("status", status)
                .param("finishedAt", timestamp(finishedAt))
                .update();
    }

    @Override
    public void startAttempt(
            UUID attemptId,
            UUID toolCallId,
            int attemptNo,
            Instant startedAt) {
        jdbcClient.sql("""
                        insert into tool_call_attempt
                            (id, tool_call_id, attempt_no, status, started_at, created_at)
                        values
                            (:id, :toolCallId, :attemptNo, 'RUNNING', :startedAt, :startedAt)
                        """)
                .param("id", attemptId)
                .param("toolCallId", toolCallId)
                .param("attemptNo", attemptNo)
                .param("startedAt", timestamp(startedAt))
                .update();
    }

    @Override
    public void finishAttempt(
            UUID attemptId,
            String status,
            String errorCode,
            boolean retryable,
            long retryDelayMs,
            long durationMs,
            Instant finishedAt) {
        jdbcClient.sql("""
                        update tool_call_attempt
                        set status = :status,
                            error_code = :errorCode,
                            retryable = :retryable,
                            retry_delay_ms = :retryDelayMs,
                            duration_ms = :durationMs,
                            finished_at = :finishedAt
                        where id = :id and status = 'RUNNING'
                        """)
                .param("id", attemptId)
                .param("status", status)
                .param("errorCode", errorCode)
                .param("retryable", retryable)
                .param("retryDelayMs", retryDelayMs)
                .param("durationMs", durationMs)
                .param("finishedAt", timestamp(finishedAt))
                .update();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
