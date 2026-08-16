package com.jundaodsj.insightops.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.agent.application.AgentRunQuery;
import com.jundaodsj.insightops.identity.application.ActorContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcAgentRunQuery implements AgentRunQuery {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public JdbcAgentRunQuery(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public RunPage listRuns(ActorContext actor, int page, int size, String status) {
        String statusClause = status == null ? "" : " and status = :status";
        JdbcClient.StatementSpec countQuery = jdbcClient.sql("""
                select count(*)
                from agent_run
                where workspace_id = :workspaceId and owner_user_id = :userId
                """ + statusClause)
                .param("workspaceId", actor.workspaceId())
                .param("userId", actor.userId());
        JdbcClient.StatementSpec listQuery = jdbcClient.sql("""
                select id, session_id, trace_id, status, question, model_provider, model_name,
                       tool_rounds, prompt_tokens, completion_tokens,
                       greatest(0, extract(epoch from (coalesce(finished_at, now()) -
                           coalesce(started_at, created_at))) * 1000)::bigint as duration_ms,
                       created_at, finished_at
                from agent_run
                where workspace_id = :workspaceId and owner_user_id = :userId
                """ + statusClause + " order by created_at desc limit :limit offset :offset")
                .param("workspaceId", actor.workspaceId())
                .param("userId", actor.userId())
                .param("limit", size)
                .param("offset", page * size);
        if (status != null) {
            countQuery = countQuery.param("status", status);
            listQuery = listQuery.param("status", status);
        }

        long total = countQuery.query(Long.class).single();
        List<RunSummary> items = listQuery.query((resultSet, rowNum) -> summary(resultSet)).list();
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / size);
        return new RunPage(items, total, page, size, totalPages);
    }

    @Override
    public Optional<RunDetail> findRun(ActorContext actor, UUID runId) {
        Optional<RunRow> row = jdbcClient.sql("""
                        select id, session_id, trace_id, status, question, answer,
                               model_provider, model_name, tool_rounds, prompt_tokens,
                               completion_tokens, estimated_cost_cny, failure_code,
                               failure_message, pricing_effective_date, citations::text as citations,
                               greatest(0, extract(epoch from (coalesce(finished_at, now()) -
                                   coalesce(started_at, created_at))) * 1000)::bigint as duration_ms,
                               started_at, finished_at, created_at
                        from agent_run
                        where id = :id and workspace_id = :workspaceId and owner_user_id = :userId
                        """)
                .param("id", runId)
                .param("workspaceId", actor.workspaceId())
                .param("userId", actor.userId())
                .query((resultSet, rowNum) -> runRow(resultSet))
                .optional();
        if (row.isEmpty()) {
            return Optional.empty();
        }

        List<RunStep> steps = jdbcClient.sql("""
                        select id, step_no, step_type, status,
                               input_payload::text as input_payload,
                               output_payload::text as output_payload,
                               case when started_at is null then null else
                                   greatest(0, extract(epoch from (coalesce(finished_at, now()) -
                                       started_at)) * 1000)::bigint end as duration_ms,
                               started_at, finished_at
                        from agent_step
                        where run_id = :runId
                        order by step_no
                        """)
                .param("runId", runId)
                .query((resultSet, rowNum) -> step(resultSet))
                .list();
        List<RunToolCall> toolCalls = jdbcClient.sql("""
                        select id, step_id, tool_name, status,
                               request_payload::text as request_payload,
                               result_payload::text as result_payload,
                               error_message, duration_ms, created_at, finished_at
                        from tool_call
                        where run_id = :runId
                        order by created_at
                        """)
                .param("runId", runId)
                .query((resultSet, rowNum) -> toolCall(resultSet))
                .list();
        RunRow value = row.orElseThrow();
        return Optional.of(new RunDetail(
                value.id(), value.sessionId(), value.traceId(), value.status(), value.question(),
                value.answer(), value.modelProvider(), value.modelName(), value.toolRounds(),
                value.promptTokens(), value.completionTokens(), value.estimatedCostCny(),
                value.pricingEffectiveDate(), value.failureCode(), value.failureMessage(),
                value.durationMs(), value.startedAt(),
                value.finishedAt(), value.createdAt(), value.sources(), steps, toolCalls));
    }

    private RunSummary summary(ResultSet resultSet) throws SQLException {
        return new RunSummary(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("session_id", UUID.class),
                resultSet.getString("trace_id"),
                resultSet.getString("status"),
                resultSet.getString("question"),
                resultSet.getString("model_provider"),
                resultSet.getString("model_name"),
                resultSet.getInt("tool_rounds"),
                nullableInteger(resultSet, "prompt_tokens"),
                nullableInteger(resultSet, "completion_tokens"),
                nullableLong(resultSet, "duration_ms"),
                instant(resultSet, "created_at"),
                instant(resultSet, "finished_at"));
    }

    private RunRow runRow(ResultSet resultSet) throws SQLException {
        return new RunRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("session_id", UUID.class),
                resultSet.getString("trace_id"),
                resultSet.getString("status"),
                resultSet.getString("question"),
                resultSet.getString("answer"),
                resultSet.getString("model_provider"),
                resultSet.getString("model_name"),
                resultSet.getInt("tool_rounds"),
                nullableInteger(resultSet, "prompt_tokens"),
                nullableInteger(resultSet, "completion_tokens"),
                resultSet.getBigDecimal("estimated_cost_cny"),
                resultSet.getObject("pricing_effective_date", java.time.LocalDate.class),
                resultSet.getString("failure_code"),
                resultSet.getString("failure_message"),
                nullableLong(resultSet, "duration_ms"),
                instant(resultSet, "started_at"),
                instant(resultSet, "finished_at"),
                instant(resultSet, "created_at"),
                stringList(resultSet.getString("citations")));
    }

    private RunStep step(ResultSet resultSet) throws SQLException {
        return new RunStep(
                resultSet.getObject("id", UUID.class),
                resultSet.getInt("step_no"),
                resultSet.getString("step_type"),
                resultSet.getString("status"),
                jsonValue(resultSet.getString("input_payload")),
                jsonValue(resultSet.getString("output_payload")),
                nullableLong(resultSet, "duration_ms"),
                instant(resultSet, "started_at"),
                instant(resultSet, "finished_at"));
    }

    private RunToolCall toolCall(ResultSet resultSet) throws SQLException {
        return new RunToolCall(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("step_id", UUID.class),
                resultSet.getString("tool_name"),
                resultSet.getString("status"),
                jsonValue(resultSet.getString("request_payload")),
                jsonValue(resultSet.getString("result_payload")),
                resultSet.getString("error_message"),
                nullableLong(resultSet, "duration_ms"),
                instant(resultSet, "created_at"),
                instant(resultSet, "finished_at"));
    }

    private Object jsonValue(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored run payload is invalid JSON", exception);
        }
    }

    private List<String> stringList(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored run citations are invalid JSON", exception);
        }
    }

    private static Integer nullableInteger(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static java.time.Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private record RunRow(
            UUID id,
            UUID sessionId,
            String traceId,
            String status,
            String question,
            String answer,
            String modelProvider,
            String modelName,
            int toolRounds,
            Integer promptTokens,
            Integer completionTokens,
            java.math.BigDecimal estimatedCostCny,
            java.time.LocalDate pricingEffectiveDate,
            String failureCode,
            String failureMessage,
            Long durationMs,
            java.time.Instant startedAt,
            java.time.Instant finishedAt,
            java.time.Instant createdAt,
            List<String> sources) {
    }
}
