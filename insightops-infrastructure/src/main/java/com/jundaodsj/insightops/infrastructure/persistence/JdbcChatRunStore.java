package com.jundaodsj.insightops.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.conversation.application.ChatRunStore;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.infrastructure.model.DeepSeekCostEstimator;
import com.jundaodsj.insightops.model.application.ModelUsage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcChatRunStore implements ChatRunStore {

    private final JdbcClient jdbcClient;
    private final DeepSeekCostEstimator costEstimator;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public JdbcChatRunStore(JdbcClient jdbcClient, DeepSeekCostEstimator costEstimator,
                            ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.costEstimator = costEstimator;
        this.objectMapper = objectMapper;
    }

    public JdbcChatRunStore(JdbcClient jdbcClient, DeepSeekCostEstimator costEstimator) {
        this(jdbcClient, costEstimator, new ObjectMapper().findAndRegisterModules());
    }

    @Override
    public List<StoredMessage> recentMessages(ActorContext actor, UUID sessionId, int limit) {
        if (sessionId == null) {
            return List.of();
        }
        if (limit < 1 || limit > 20) {
            throw new IllegalArgumentException("message history limit must be between 1 and 20");
        }
        return jdbcClient.sql("""
                        select role, content
                        from (
                            select message.role, message.content, message.sequence_no
                            from conversation_message message
                            join conversation_session session on session.id = message.session_id
                            where message.session_id = :sessionId
                              and session.workspace_id = :workspaceId
                              and session.owner_user_id = :userId
                              and session.status = 'ACTIVE'
                            order by message.sequence_no desc
                            limit :limit
                        ) recent
                        order by sequence_no
                        """)
                .param("sessionId", sessionId)
                .param("workspaceId", actor.workspaceId())
                .param("userId", actor.userId())
                .param("limit", limit)
                .query((resultSet, rowNum) -> new StoredMessage(
                        resultSet.getString("role"),
                        resultSet.getString("content")))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SessionHistory> sessionHistory(ActorContext actor, UUID sessionId, int limit) {
        if (sessionId == null) {
            return Optional.empty();
        }
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("session history limit must be between 1 and 200");
        }
        Optional<String> title = jdbcClient.sql("""
                        select title
                        from conversation_session
                        where id = :sessionId
                          and workspace_id = :workspaceId
                          and owner_user_id = :userId
                          and status = 'ACTIVE'
                        """)
                .param("sessionId", sessionId)
                .param("workspaceId", actor.workspaceId())
                .param("userId", actor.userId())
                .query(String.class)
                .optional();
        if (title.isEmpty()) {
            return Optional.empty();
        }

        List<HistoryMessage> latestFirst = jdbcClient.sql("""
                        select id, role, content, citations, sequence_no, created_at
                        from conversation_message
                        where session_id = :sessionId
                        order by sequence_no desc
                        limit :limit
                        """)
                .param("sessionId", sessionId)
                .param("limit", limit + 1)
                .query((resultSet, rowNum) -> new HistoryMessage(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("role"),
                        resultSet.getString("content"),
                        citations(resultSet.getString("citations")),
                        resultSet.getInt("sequence_no"),
                        resultSet.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
        boolean hasEarlierMessages = latestFirst.size() > limit;
        List<HistoryMessage> messages = new java.util.ArrayList<>(
                latestFirst.subList(0, Math.min(latestFirst.size(), limit)));
        java.util.Collections.reverse(messages);
        return Optional.of(new SessionHistory(
                sessionId,
                title.orElseThrow(),
                List.copyOf(messages),
                hasEarlierMessages));
    }

    @Override
    public boolean ownsRun(ActorContext actor, UUID runId) {
        return jdbcClient.sql("""
                select count(*) = 1
                from agent_run run
                where run.id = :runId
                  and run.workspace_id = :workspaceId
                  and run.owner_user_id = :userId
                """)
                .param("runId", runId)
                .param("workspaceId", actor.workspaceId())
                .param("userId", actor.userId())
                .query(Boolean.class)
                .single();
    }

    @Override
    @Transactional
    public UUID startRun(
            ActorContext actor,
            UUID runId,
            UUID requestedSessionId,
            String traceId,
            String question,
            Instant startedAt) {
        UUID sessionId = requestedSessionId == null
                ? createSession(actor, question, startedAt)
                : lockActiveSession(actor, requestedSessionId);
        int sequenceNo = nextMessageSequence(sessionId);

        jdbcClient.sql("""
                        insert into conversation_message
                            (id, session_id, role, content, sequence_no, created_at)
                        values
                            (:id, :sessionId, 'USER', :content, :sequenceNo, :createdAt)
                        """)
                .param("id", UUID.randomUUID())
                .param("sessionId", sessionId)
                .param("content", question)
                .param("sequenceNo", sequenceNo)
                .param("createdAt", timestamp(startedAt))
                .update();

        jdbcClient.sql("""
                        insert into agent_run
                            (id, workspace_id, owner_user_id, session_id, trace_id, status, question,
                             started_at, created_at)
                        values
                            (:id, :workspaceId, :userId, :sessionId, :traceId, 'RUNNING', :question,
                             :startedAt, :startedAt)
                        """)
                .param("id", runId)
                .param("workspaceId", actor.workspaceId())
                .param("userId", actor.userId())
                .param("sessionId", sessionId)
                .param("traceId", traceId)
                .param("question", question)
                .param("startedAt", timestamp(startedAt))
                .update();

        touchSession(sessionId, startedAt);
        return sessionId;
    }

    @Override
    @Transactional
    public void succeedRun(
            UUID runId,
            String answer,
            String provider,
            String model,
            ModelUsage usage,
            List<String> citations,
            Instant finishedAt) {
        RunState run = lockRun(runId);
        if (!"RUNNING".equals(run.status())) {
            return;
        }

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", runId);
        parameters.put("answer", answer);
        parameters.put("provider", provider);
        parameters.put("model", model);
        parameters.put("promptTokens", usage == null ? null : usage.inputTokens());
        parameters.put("completionTokens", usage == null ? null : usage.outputTokens());
        DeepSeekCostEstimator.CostEstimate cost = costEstimator.estimate(usage).orElse(null);
        parameters.put("estimatedCostCny", cost == null ? null : cost.cny());
        parameters.put("pricingEffectiveDate", cost == null ? null : cost.pricingEffectiveDate());
        parameters.put("finishedAt", timestamp(finishedAt));
        jdbcClient.sql("""
                        update agent_run
                        set status = 'SUCCEEDED',
                            answer = :answer,
                            citations = cast(:citations as jsonb),
                            model_provider = :provider,
                            model_name = :model,
                            prompt_tokens = :promptTokens,
                            completion_tokens = :completionTokens,
                            estimated_cost_cny = :estimatedCostCny,
                            pricing_effective_date = :pricingEffectiveDate,
                            finished_at = :finishedAt
                        where id = :id and status = 'RUNNING'
                        """)
                .params(parameters)
                .param("citations", jsonArray(citations))
                .update();

        lockRunSession(run.sessionId());
        jdbcClient.sql("""
                        insert into conversation_message
                            (id, session_id, role, content, citations, sequence_no, created_at)
                        values
                            (:id, :sessionId, 'ASSISTANT', :content,
                             cast(:citations as jsonb), :sequenceNo, :createdAt)
                        """)
                .param("id", UUID.randomUUID())
                .param("sessionId", run.sessionId())
                .param("content", answer)
                .param("citations", jsonArray(citations))
                .param("sequenceNo", nextMessageSequence(run.sessionId()))
                .param("createdAt", timestamp(finishedAt))
                .update();
        touchSession(run.sessionId(), finishedAt);
    }

    @Override
    @Transactional
    public void cancelRun(UUID runId, String partialAnswer, Instant finishedAt) {
        finishWithoutAssistant(runId, "CANCELLED", partialAnswer, null, finishedAt);
    }

    @Override
    @Transactional
    public void failRun(
            UUID runId,
            String partialAnswer,
            String failureCode,
            Instant finishedAt) {
        finishWithoutAssistant(runId, "FAILED", partialAnswer, failureCode, finishedAt);
    }

    private UUID createSession(ActorContext actor, String question, Instant createdAt) {
        UUID sessionId = UUID.randomUUID();
        jdbcClient.sql("""
                        insert into conversation_session
                            (id, workspace_id, owner_user_id, title, status, created_at, updated_at)
                        values
                            (:id, :workspaceId, :userId, :title, 'ACTIVE', :createdAt, :createdAt)
                        """)
                .param("id", sessionId)
                .param("workspaceId", actor.workspaceId())
                .param("userId", actor.userId())
                .param("title", title(question))
                .param("createdAt", timestamp(createdAt))
                .update();
        return sessionId;
    }

    private UUID lockActiveSession(ActorContext actor, UUID sessionId) {
        return jdbcClient.sql("""
                        select id
                        from conversation_session
                        where id = :id and workspace_id = :workspaceId
                          and owner_user_id = :userId and status = 'ACTIVE'
                        for update
                        """)
                .param("id", sessionId)
                .param("workspaceId", actor.workspaceId())
                .param("userId", actor.userId())
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Conversation session is missing or inactive"));
    }

    private UUID lockRunSession(UUID sessionId) {
        return jdbcClient.sql("""
                        select id
                        from conversation_session
                        where id = :id and status = 'ACTIVE'
                        for update
                        """)
                .param("id", sessionId)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Conversation session is missing or inactive"));
    }

    private RunState lockRun(UUID runId) {
        return jdbcClient.sql("""
                        select session_id, status
                        from agent_run
                        where id = :id
                        for update
                        """)
                .param("id", runId)
                .query((resultSet, rowNum) -> new RunState(
                        resultSet.getObject("session_id", UUID.class),
                        resultSet.getString("status")))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Agent run does not exist"));
    }

    private int nextMessageSequence(UUID sessionId) {
        return jdbcClient.sql("""
                        select coalesce(max(sequence_no), 0) + 1
                        from conversation_message
                        where session_id = :sessionId
                        """)
                .param("sessionId", sessionId)
                .query(Integer.class)
                .single();
    }

    private void finishWithoutAssistant(
            UUID runId,
            String status,
            String partialAnswer,
            String failureCode,
            Instant finishedAt) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", runId);
        parameters.put("status", status);
        parameters.put("answer", partialAnswer == null || partialAnswer.isBlank() ? null : partialAnswer);
        parameters.put("failureCode", failureCode);
        parameters.put("finishedAt", timestamp(finishedAt));
        jdbcClient.sql("""
                        update agent_run
                        set status = :status,
                            answer = :answer,
                            failure_code = :failureCode,
                            finished_at = :finishedAt
                        where id = :id and status = 'RUNNING'
                        """)
                .params(parameters)
                .update();
    }

    private void touchSession(UUID sessionId, Instant updatedAt) {
        jdbcClient.sql("update conversation_session set updated_at = :updatedAt where id = :id")
                .param("updatedAt", timestamp(updatedAt))
                .param("id", sessionId)
                .update();
    }

    private static String title(String question) {
        String normalized = question.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80) + "…";
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String jsonArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return values.stream()
                .distinct()
                .map(JdbcChatRunStore::jsonString)
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static String jsonString(String value) {
        String escaped = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
        return "\"" + escaped + "\"";
    }

    private List<String> citations(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return List.copyOf(objectMapper.readValue(value, new TypeReference<List<String>>() { }));
        }
        catch (Exception exception) {
            throw new IllegalStateException("Unable to read stored citations", exception);
        }
    }

    private record RunState(UUID sessionId, String status) {
    }
}
