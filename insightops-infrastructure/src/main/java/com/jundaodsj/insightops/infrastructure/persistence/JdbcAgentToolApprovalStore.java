package com.jundaodsj.insightops.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.tool.application.AgentToolApprovalStore;
import com.jundaodsj.insightops.tool.application.registry.AgentToolNames;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcAgentToolApprovalStore implements AgentToolApprovalStore {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public JdbcAgentToolApprovalStore(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public Approval request(Request command) {
        jdbcClient.sql("""
                insert into agent_tool_approval
                    (id, run_id, step_id, tool_call_id, user_id, workspace_id,
                     tool_name, summary, request_payload, idempotency_key, status,
                     expires_at, created_at, updated_at)
                values
                    (:id, :runId, :stepId, :toolCallId, :userId, :workspaceId,
                     :toolName, :summary, cast(:payload as jsonb), :idempotencyKey,
                     'PENDING', :expiresAt, :now, :now)
                """)
                .param("id", command.id())
                .param("runId", command.runId())
                .param("stepId", command.stepId())
                .param("toolCallId", command.toolCallId())
                .param("userId", command.userId())
                .param("workspaceId", command.workspaceId())
                .param("toolName", command.toolName())
                .param("summary", command.summary())
                .param("payload", command.requestPayload())
                .param("idempotencyKey", command.idempotencyKey())
                .param("expiresAt", timestamp(command.expiresAt()))
                .param("now", timestamp(command.createdAt()))
                .update();
        return findInternal(command.id()).orElseThrow();
    }

    @Override
    public List<Approval> list(ActorContext actor, String status) {
        if (status == null || status.isBlank()) {
            return jdbcClient.sql("""
                    select * from agent_tool_approval
                    where user_id = :userId and workspace_id = :workspaceId
                    order by created_at desc limit 200
                    """)
                    .param("userId", actor.userId())
                    .param("workspaceId", actor.workspaceId())
                    .query((rs, rowNum) -> approval(rs)).list();
        }
        return jdbcClient.sql("""
                select * from agent_tool_approval
                where user_id = :userId and workspace_id = :workspaceId
                  and status = :status
                order by created_at desc limit 200
                """)
                .param("userId", actor.userId())
                .param("workspaceId", actor.workspaceId())
                .param("status", status.strip().toUpperCase())
                .query((rs, rowNum) -> approval(rs)).list();
    }

    @Override
    public Optional<Approval> find(ActorContext actor, UUID approvalId) {
        return jdbcClient.sql("""
                select * from agent_tool_approval
                where id = :id and user_id = :userId and workspace_id = :workspaceId
                """)
                .param("id", approvalId)
                .param("userId", actor.userId())
                .param("workspaceId", actor.workspaceId())
                .query((rs, rowNum) -> approval(rs)).optional();
    }

    @Override
    @Transactional
    public Approval approve(ActorContext actor, UUID approvalId, String comment, Instant now) {
        Approval approval = lock(actor, approvalId);
        if (!"PENDING".equals(approval.status())) return approval;
        if (!now.isBefore(approval.expiresAt())) {
            finishWithoutExecution(approval, "EXPIRED", "APPROVAL_EXPIRED", comment, now);
            return findInternal(approvalId).orElseThrow();
        }
        if (!AgentToolNames.USER_MEMORY_UPSERT.equals(approval.toolName())) {
            finishWithoutExecution(approval, "FAILED", "APPROVAL_TOOL_UNSUPPORTED", comment, now);
            return findInternal(approvalId).orElseThrow();
        }

        Map<String, Object> input = parse(approval.requestPayload());
        String key = text(input, "key");
        String value = text(input, "value");
        String category = text(input, "category").toUpperCase();
        Optional<Map<String, Object>> previous = memoryForUpdate(actor, key);
        Map<String, Object> before = previous.orElseGet(() -> Map.of("exists", false));
        UUID memoryId = previous.map(item -> UUID.fromString(String.valueOf(item.get("id"))))
                .orElseGet(() -> UUID.nameUUIDFromBytes(
                        ("approval:" + approval.id()).getBytes(StandardCharsets.UTF_8)));
        if (previous.isPresent()) {
            jdbcClient.sql("""
                    update user_memory set memory_value = :value, category = :category,
                        enabled = true, updated_at = :now
                    where id = :id and user_id = :userId and workspace_id = :workspaceId
                    """)
                    .param("value", value).param("category", category)
                    .param("now", timestamp(now)).param("id", memoryId)
                    .param("userId", actor.userId()).param("workspaceId", actor.workspaceId())
                    .update();
        } else {
            jdbcClient.sql("""
                    insert into user_memory
                        (id, user_id, workspace_id, memory_key, memory_value, category,
                         enabled, created_at, updated_at)
                    values (:id, :userId, :workspaceId, :key, :value, :category,
                            true, :now, :now)
                    """)
                    .param("id", memoryId).param("userId", actor.userId())
                    .param("workspaceId", actor.workspaceId()).param("key", key)
                    .param("value", value).param("category", category)
                    .param("now", timestamp(now)).update();
        }
        Map<String, Object> after = memoryForUpdate(actor, key).orElseThrow();
        jdbcClient.sql("""
                insert into agent_tool_effect
                    (id, approval_id, effect_key, status, before_payload,
                     after_payload, applied_at)
                values (:id, :approvalId, :effectKey, 'APPLIED',
                        cast(:before as jsonb), cast(:after as jsonb), :now)
                on conflict (approval_id) do nothing
                """)
                .param("id", UUID.randomUUID()).param("approvalId", approval.id())
                .param("effectKey", "approval:" + approval.id())
                .param("before", json(before)).param("after", json(after))
                .param("now", timestamp(now)).update();
        String result = json(Map.of("status", "EXECUTED", "memory", after));
        finishExecuted(approval, result, comment, now);
        return findInternal(approvalId).orElseThrow();
    }

    @Override
    @Transactional
    public Approval reject(ActorContext actor, UUID approvalId, String comment, Instant now) {
        Approval approval = lock(actor, approvalId);
        if (!"PENDING".equals(approval.status())) return approval;
        finishWithoutExecution(approval, "REJECTED", "APPROVAL_REJECTED", comment, now);
        return findInternal(approvalId).orElseThrow();
    }

    @Override
    @Transactional
    public Approval compensate(ActorContext actor, UUID approvalId, String comment, Instant now) {
        Approval approval = lock(actor, approvalId);
        if ("COMPENSATED".equals(approval.status())) return approval;
        if (!"EXECUTED".equals(approval.status())) {
            throw new ApprovalException("APPROVAL_NOT_COMPENSATABLE",
                    "Only an executed approval can be compensated");
        }
        Map<String, Object> effect = jdbcClient.sql("""
                select before_payload::text as before_payload,
                       after_payload::text as after_payload
                from agent_tool_effect where approval_id = :approvalId for update
                """)
                .param("approvalId", approvalId)
                .query((rs, rowNum) -> Map.<String, Object>of(
                        "before", rs.getString("before_payload"),
                        "after", rs.getString("after_payload")))
                .optional().orElseThrow(() -> new ApprovalException(
                        "APPROVAL_EFFECT_NOT_FOUND", "Approval effect was not found"));
        Map<String, Object> before = parse(String.valueOf(effect.get("before")));
        Map<String, Object> after = parse(String.valueOf(effect.get("after")));
        UUID memoryId = UUID.fromString(String.valueOf(after.get("id")));
        Optional<Map<String, Object>> current = memoryByIdForUpdate(actor, memoryId);
        if (current.isPresent() && !sameMemorySnapshot(current.orElseThrow(), after)) {
            throw new ApprovalException("APPROVAL_EFFECT_CONFLICT",
                    "Memory changed after approval; compensation was not applied");
        }
        if (Boolean.TRUE.equals(before.get("exists")) && current.isEmpty()) {
            throw new ApprovalException("APPROVAL_EFFECT_CONFLICT",
                    "Memory changed after approval; compensation was not applied");
        }
        if (!Boolean.TRUE.equals(before.get("exists"))) {
            if (current.isPresent()) {
                jdbcClient.sql("""
                        delete from user_memory
                        where id = :id and user_id = :userId and workspace_id = :workspaceId
                        """)
                        .param("id", memoryId).param("userId", actor.userId())
                        .param("workspaceId", actor.workspaceId()).update();
            }
        } else {
            restoreMemory(actor, before, now);
        }
        jdbcClient.sql("""
                update agent_tool_effect set status = 'COMPENSATED', compensated_at = :now
                where approval_id = :approvalId and status = 'APPLIED'
                """)
                .param("now", timestamp(now)).param("approvalId", approvalId).update();
        jdbcClient.sql("""
                update agent_tool_approval
                set status = 'COMPENSATED', decision_comment = :comment,
                    compensated_at = :now, updated_at = :now
                where id = :id and status = 'EXECUTED'
                """)
                .param("comment", nullableComment(comment)).param("now", timestamp(now))
                .param("id", approvalId).update();
        updateToolAudit(approval, "COMPENSATED",
                json(Map.of("status", "COMPENSATED", "restored", before)), null, now);
        return findInternal(approvalId).orElseThrow();
    }

    private Approval lock(ActorContext actor, UUID id) {
        return jdbcClient.sql("""
                select * from agent_tool_approval
                where id = :id and user_id = :userId and workspace_id = :workspaceId
                for update
                """)
                .param("id", id).param("userId", actor.userId())
                .param("workspaceId", actor.workspaceId())
                .query((rs, rowNum) -> approval(rs)).optional()
                .orElseThrow(() -> new ApprovalException(
                        "APPROVAL_NOT_FOUND", "Approval was not found"));
    }

    private Optional<Approval> findInternal(UUID id) {
        return jdbcClient.sql("select * from agent_tool_approval where id = :id")
                .param("id", id).query((rs, rowNum) -> approval(rs)).optional();
    }

    private Optional<Map<String, Object>> memoryForUpdate(ActorContext actor, String key) {
        return jdbcClient.sql("""
                select id, memory_key, memory_value, category, enabled,
                       created_at, updated_at
                from user_memory
                where user_id = :userId and workspace_id = :workspaceId
                  and memory_key = :key
                for update
                """)
                .param("userId", actor.userId()).param("workspaceId", actor.workspaceId())
                .param("key", key)
                .query((rs, rowNum) -> memory(rs)).optional();
    }

    private Optional<Map<String, Object>> memoryByIdForUpdate(
            ActorContext actor, UUID memoryId) {
        return jdbcClient.sql("""
                select id, memory_key, memory_value, category, enabled,
                       created_at, updated_at
                from user_memory
                where id = :id and user_id = :userId and workspace_id = :workspaceId
                for update
                """)
                .param("id", memoryId).param("userId", actor.userId())
                .param("workspaceId", actor.workspaceId())
                .query((rs, rowNum) -> memory(rs)).optional();
    }

    private static boolean sameMemorySnapshot(
            Map<String, Object> current, Map<String, Object> applied) {
        return Objects.equals(current.get("id"), applied.get("id"))
                && Objects.equals(current.get("key"), applied.get("key"))
                && Objects.equals(current.get("value"), applied.get("value"))
                && Objects.equals(current.get("category"), applied.get("category"))
                && Objects.equals(current.get("enabled"), applied.get("enabled"));
    }

    private void restoreMemory(ActorContext actor, Map<String, Object> before, Instant now) {
        jdbcClient.sql("""
                insert into user_memory
                    (id, user_id, workspace_id, memory_key, memory_value, category,
                     enabled, created_at, updated_at)
                values (:id, :userId, :workspaceId, :key, :value, :category,
                        :enabled, cast(:createdAt as timestamptz), :now)
                on conflict (id) do update set
                    memory_key = excluded.memory_key,
                    memory_value = excluded.memory_value,
                    category = excluded.category,
                    enabled = excluded.enabled,
                    updated_at = excluded.updated_at
                """)
                .param("id", UUID.fromString(String.valueOf(before.get("id"))))
                .param("userId", actor.userId()).param("workspaceId", actor.workspaceId())
                .param("key", String.valueOf(before.get("key")))
                .param("value", String.valueOf(before.get("value")))
                .param("category", String.valueOf(before.get("category")))
                .param("enabled", Boolean.TRUE.equals(before.get("enabled")))
                .param("createdAt", String.valueOf(before.get("createdAt")))
                .param("now", timestamp(now)).update();
    }

    private void finishExecuted(Approval approval, String result, String comment, Instant now) {
        jdbcClient.sql("""
                update agent_tool_approval
                set status = 'EXECUTED', result_payload = cast(:result as jsonb),
                    decision_comment = :comment, decided_at = :now,
                    executed_at = :now, updated_at = :now
                where id = :id and status = 'PENDING'
                """)
                .param("result", result).param("comment", nullableComment(comment))
                .param("now", timestamp(now)).param("id", approval.id()).update();
        updateToolAudit(approval, "SUCCEEDED", result, null, now);
        jdbcClient.sql("update agent_run set tool_rounds = tool_rounds + 1 where id = :id")
                .param("id", approval.runId()).update();
    }

    private void finishWithoutExecution(
            Approval approval, String status, String errorCode, String comment, Instant now) {
        jdbcClient.sql("""
                update agent_tool_approval
                set status = :status, error_code = :errorCode,
                    decision_comment = :comment, decided_at = :now, updated_at = :now
                where id = :id and status = 'PENDING'
                """)
                .param("status", status).param("errorCode", errorCode)
                .param("comment", nullableComment(comment)).param("now", timestamp(now))
                .param("id", approval.id()).update();
        updateToolAudit(approval, "REJECTED".equals(status) ? "REJECTED" : "FAILED",
                json(Map.of("status", status, "errorCode", errorCode)), errorCode, now);
    }

    private void updateToolAudit(
            Approval approval, String status, String result, String errorCode, Instant now) {
        jdbcClient.sql("""
                update tool_call
                set status = :status, result_payload = cast(:result as jsonb),
                    error_message = :errorCode, finished_at = :now
                where id = :id and status in ('WAITING_APPROVAL', 'SUCCEEDED')
                """)
                .param("status", status).param("result", result)
                .param("errorCode", errorCode).param("now", timestamp(now))
                .param("id", approval.toolCallId()).update();
        jdbcClient.sql("""
                update agent_step
                set status = :status, output_payload = cast(:result as jsonb), finished_at = :now
                where id = (select step_id from agent_tool_approval where id = :approvalId)
                  and status in ('WAITING_APPROVAL', 'SUCCEEDED')
                """)
                .param("status", status).param("result", result)
                .param("now", timestamp(now)).param("approvalId", approval.id()).update();
    }

    private Map<String, Object> parse(String json) {
        try { return objectMapper.readValue(json, MAP_TYPE); }
        catch (JsonProcessingException exception) {
            throw new ApprovalException("APPROVAL_PAYLOAD_INVALID", "Invalid approval payload", exception);
        }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) {
            throw new ApprovalException("APPROVAL_SERIALIZATION_FAILED", "Unable to serialize approval", exception);
        }
    }

    private static String text(Map<String, Object> input, String key) {
        Object value = input.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new ApprovalException("APPROVAL_PAYLOAD_INVALID", "Missing field: " + key);
        }
        return text.strip();
    }

    private static Map<String, Object> memory(ResultSet rs) throws SQLException {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("exists", true);
        result.put("id", rs.getObject("id", UUID.class).toString());
        result.put("key", rs.getString("memory_key"));
        result.put("value", rs.getString("memory_value"));
        result.put("category", rs.getString("category"));
        result.put("enabled", rs.getBoolean("enabled"));
        result.put("createdAt", rs.getObject("created_at", OffsetDateTime.class).toInstant().toString());
        result.put("updatedAt", rs.getObject("updated_at", OffsetDateTime.class).toInstant().toString());
        return result;
    }

    private static Approval approval(ResultSet rs) throws SQLException {
        return new Approval(
                rs.getObject("id", UUID.class), rs.getObject("run_id", UUID.class),
                rs.getObject("tool_call_id", UUID.class), rs.getString("tool_name"),
                rs.getString("summary"), rs.getString("status"),
                rs.getString("request_payload"), rs.getString("result_payload"),
                rs.getString("error_code"), rs.getString("decision_comment"),
                instant(rs, "expires_at"), instant(rs, "decided_at"),
                instant(rs, "executed_at"), instant(rs, "compensated_at"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String nullableComment(String comment) {
        return comment == null || comment.isBlank() ? null : comment.strip();
    }
}
