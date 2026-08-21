package com.jundaodsj.insightops.server.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.server.chat.AgentToolDispatcher;
import com.jundaodsj.insightops.tool.application.AgentToolApprovalStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AgentToolApprovalService {

    private static final Duration APPROVAL_TTL = Duration.ofMinutes(30);
    private static final Set<String> MEMORY_CATEGORIES = Set.of(
            "PROFILE", "PREFERENCE", "INTEREST", "CONSTRAINT");
    private final AgentToolApprovalStore store;
    private final ObjectMapper objectMapper;

    public AgentToolApprovalService(AgentToolApprovalStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PendingApproval requestMemoryUpsert(
            AgentToolDispatcher.ExecutionContext context,
            RegisteredToolExecutionService.Session session,
            Map<String, Object> input) {
        String key = requiredText(input, "key");
        String value = requiredText(input, "value");
        String category = requiredText(input, "category").toUpperCase(Locale.ROOT);
        if (!MEMORY_CATEGORIES.contains(category)) {
            throw new AgentToolApprovalStore.ApprovalException(
                    "APPROVAL_INPUT_INVALID", "Unsupported memory category");
        }
        Map<String, Object> normalized = Map.of(
                "key", key, "value", value, "category", category);
        Instant now = Instant.now();
        UUID approvalId = UUID.randomUUID();
        Instant expiresAt = now.plus(APPROVAL_TTL);
        String idempotencyKey = context.runId() + ":" + session.toolName()
                + ":" + context.round() + ":" + context.invocationNo();
        String summary = "写入长期记忆：“ + limited(key, 80) + ” = “"
                + limited(value, 120) + "”";
        AgentToolApprovalStore.Approval approval = store.request(
                new AgentToolApprovalStore.Request(
                        approvalId, session.runId(), session.stepId(), session.toolCallId(),
                        context.userId(), context.workspaceId(), session.toolName(), summary,
                        json(normalized), idempotencyKey, expiresAt, now));
        Map<String, Object> pending = Map.of(
                "status", "WAITING_APPROVAL",
                "approvalId", approval.id().toString(),
                "expiresAt", approval.expiresAt().toString());
        session.waitForApproval(pending);
        return new PendingApproval(approval.id(), approval.expiresAt(), summary, pending);
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) {
            throw new AgentToolApprovalStore.ApprovalException(
                    "APPROVAL_SERIALIZATION_FAILED", "Unable to serialize approval", exception);
        }
    }

    private static String requiredText(Map<String, Object> input, String key) {
        Object value = input.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new AgentToolApprovalStore.ApprovalException(
                    "APPROVAL_INPUT_INVALID", "Missing approval field: " + key);
        }
        return text.strip();
    }

    private static String limited(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    public record PendingApproval(
            UUID id, Instant expiresAt, String summary, Map<String, Object> observation) { }
}
