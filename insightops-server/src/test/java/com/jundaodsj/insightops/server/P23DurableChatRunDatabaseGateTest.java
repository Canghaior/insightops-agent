package com.jundaodsj.insightops.server;

import com.jundaodsj.insightops.conversation.application.DurableChatRunStore;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcDurableChatRunStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "INSIGHTOPS_CHAIN_GATE", matches = "true")
class P23DurableChatRunDatabaseGateTest {

    private static final UUID WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final ActorContext ACTOR = new ActorContext(USER, WORKSPACE);
    private static final String SCHEMA = "p23_gate_" + UUID.randomUUID().toString().replace("-", "");

    private static String databaseUrl;
    private static String username;
    private static String password;
    private static DataSource dataSource;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void migrate() throws Exception {
        databaseUrl = environment("DB_URL", "jdbc:postgresql://localhost:55432/insightops");
        username = environment("DB_USERNAME", "insightops");
        password = environment("DB_PASSWORD", "insightops_dev");
        DriverManagerDataSource root = new DriverManagerDataSource(databaseUrl, username, password);
        try (Connection connection = root.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("create extension if not exists vector with schema public");
        }
        String schemaUrl = databaseUrl + (databaseUrl.contains("?") ? "&" : "?")
                + "currentSchema=" + SCHEMA;
        dataSource = new DriverManagerDataSource(schemaUrl, username, password);
        var result = Flyway.configure().dataSource(dataSource).schemas(SCHEMA)
                .defaultSchema(SCHEMA).createSchemas(true)
                .locations("classpath:db/migration").load().migrate();
        assertThat(result.migrationsExecuted).isEqualTo(37);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (databaseUrl == null) return;
        DriverManagerDataSource root = new DriverManagerDataSource(databaseUrl, username, password);
        try (Connection connection = root.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + SCHEMA + " cascade");
        }
    }

    @Test
    void reclaimsExpiredRunRestoresCheckpointAndFencesTheOldWorker() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        JdbcDurableChatRunStore store = new JdbcDurableChatRunStore(jdbc);
        UUID runId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID checkpointId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        UUID toolCallId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        Instant started = Instant.parse("2026-08-22T10:00:00Z");

        jdbc.sql("""
                        insert into conversation_session
                            (id, workspace_id, owner_user_id, title, status, created_at, updated_at)
                        values (:id, :workspaceId, :userId, 'P2.3 gate', 'ACTIVE', :now, :now)
                        """).param("id", sessionId).param("workspaceId", WORKSPACE)
                .param("userId", USER).param("now", Timestamp.from(started)).update();
        jdbc.sql("""
                        insert into agent_run
                            (id, workspace_id, owner_user_id, session_id, trace_id, status,
                             question, started_at, created_at)
                        values (:id, :workspaceId, :userId, :sessionId, :traceId, 'RUNNING',
                                'durable chat gate', :now, :now)
                        """).param("id", runId).param("workspaceId", WORKSPACE)
                .param("userId", USER).param("sessionId", sessionId)
                .param("traceId", "p23-db-" + runId).param("now", Timestamp.from(started)).update();

        tx(() -> {
            store.enqueue(new DurableChatRunStore.WorkDraft(
                            runId, WORKSPACE, USER, sessionId, "p23-db-" + runId,
                            true, "SYSTEM_ADMIN", "question", "context", null, 3, started),
                    "{\"type\":\"started\"}");
            return null;
        });
        assertThat(store.queueSnapshot(started.plusSeconds(1)))
                .isEqualTo(new DurableChatRunStore.QueueSnapshot(1, 0, 0, 1, 0));
        DurableChatRunStore.WorkLease first = tx(() -> store.claim(
                "server-a", 1, 3, Duration.ofSeconds(30), started).getFirst());
        assertThat(store.queueSnapshot(started.plusSeconds(10)))
                .isEqualTo(new DurableChatRunStore.QueueSnapshot(0, 1, 0, 0, 10));

        jdbc.sql("""
                        insert into agent_plan (id, run_id, version, status, max_parallelism, created_at)
                        values (:id, :runId, 1, 'ACTIVE', 1, :now)
                        """).param("id", planId).param("runId", runId)
                .param("now", Timestamp.from(started.plusSeconds(1))).update();
        jdbc.sql("""
                        insert into agent_step
                            (id, run_id, step_no, step_type, status, input_payload, started_at, created_at)
                        values (:id, :runId, 1, 'TOOL', 'RUNNING', '{}'::jsonb, :now, :now)
                        """).param("id", stepId).param("runId", runId)
                .param("now", Timestamp.from(started.plusSeconds(1))).update();
        jdbc.sql("""
                        insert into tool_call
                            (id, run_id, step_id, tool_name, status, idempotency_key,
                             request_payload, created_at)
                        values (:id, :runId, :stepId, 'knowledge_hybrid_search', 'RUNNING',
                                :key, '{}'::jsonb, :now)
                        """).param("id", toolCallId).param("runId", runId)
                .param("stepId", stepId).param("key", "p23-" + runId)
                .param("now", Timestamp.from(started.plusSeconds(1))).update();
        jdbc.sql("""
                        insert into agent_plan_node
                            (id, plan_id, run_id, provider_tool_call_id, plan_round, position,
                             tool_name, risk_level, required, status, input_payload, tool_call_id,
                             started_at, created_at, updated_at)
                        values (:id, :planId, :runId, 'provider-call', 1, 1,
                                'knowledge_hybrid_search', 'READ_ONLY', true, 'RUNNING',
                                '{}'::jsonb, :toolCallId, :now, :now, :now)
                        """).param("id", nodeId).param("planId", planId).param("runId", runId)
                .param("toolCallId", toolCallId).param("now", Timestamp.from(started.plusSeconds(1))).update();
        jdbc.sql("""
                        insert into agent_plan_checkpoint
                            (id, plan_id, run_id, workspace_id, user_id, sequence, reason,
                             status, state_json, budget_json, created_at)
                        values (:id, :planId, :runId, :workspaceId, :userId, 1, 'SAFE_POINT',
                                'AVAILABLE', '{"evidence":["saved"]}'::jsonb,
                                '{"usedNodes":1,"usedToolAttempts":1,"usedModelTokens":50,
                                  "estimatedCostCny":0.001,"status":"ACTIVE"}'::jsonb, :now)
                        """).param("id", checkpointId).param("planId", planId).param("runId", runId)
                .param("workspaceId", WORKSPACE).param("userId", USER)
                .param("now", Timestamp.from(started.plusSeconds(2))).update();

        Instant takeoverAt = started.plusSeconds(31);
        assertThat(store.queueSnapshot(takeoverAt))
                .isEqualTo(new DurableChatRunStore.QueueSnapshot(0, 1, 1, 0, 31));
        DurableChatRunStore.WorkLease second = tx(() -> store.claim(
                "server-b", 1, 3, Duration.ofSeconds(30), takeoverAt).getFirst());
        DurableChatRunStore.AttemptPreparation preparation = tx(() -> store.prepareAttempt(
                runId, second.leaseToken(), takeoverAt.plusSeconds(1)));

        assertThat(second.reclaimed()).isTrue();
        assertThat(second.attemptCount()).isEqualTo(2);
        assertThat(preparation.recovered()).isTrue();
        assertThat(second.reclaimDelay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(preparation.recoveryCheckpointId()).isEqualTo(checkpointId);
        assertThat(store.queueSnapshot(takeoverAt))
                .isEqualTo(new DurableChatRunStore.QueueSnapshot(0, 1, 0, 0, 0));
        assertThat(jdbc.sql("select status from agent_plan where id = :id")
                .param("id", planId).query(String.class).single()).isEqualTo("SUPERSEDED");
        assertThat(jdbc.sql("select status from agent_plan_node where id = :id")
                .param("id", nodeId).query(String.class).single()).isEqualTo("CANCELLED");
        assertThat(jdbc.sql("select status from tool_call where id = :id")
                .param("id", toolCallId).query(String.class).single()).isEqualTo("FAILED");
        assertThat(jdbc.sql("select status from agent_step where id = :id")
                .param("id", stepId).query(String.class).single()).isEqualTo("FAILED");

        assertThat(tx(() -> store.appendEvent(runId, first.leaseToken(),
                "delta", "{\"content\":\"stale\"}", takeoverAt.plusSeconds(2)))).isEmpty();
        assertThat(tx(() -> store.appendEvent(runId, second.leaseToken(),
                "run_recovered", "{\"checkpointId\":\"" + checkpointId + "\"}",
                takeoverAt.plusSeconds(2)))).contains(2L);

        assertThat(store.requestCancel(ACTOR, runId, takeoverAt.plusSeconds(3))).isTrue();
        assertThat(tx(() -> store.renewLease(runId, second.leaseToken(),
                Duration.ofSeconds(30), takeoverAt.plusSeconds(4))))
                .isEqualTo(DurableChatRunStore.LeaseControl.CANCEL_REQUESTED);
        assertThat(tx(() -> store.markTerminal(
                runId, second.leaseToken(), "CANCELLED", null,
                "cancelled", "{\"type\":\"cancelled\"}", takeoverAt.plusSeconds(4))))
                .isTrue();
        assertThat(tx(() -> store.markTerminal(
                runId, first.leaseToken(), "FAILED", "STALE",
                "error", "{\"type\":\"error\"}", takeoverAt.plusSeconds(4))))
                .isFalse();

        DurableChatRunStore.WorkView view = store.findOwned(ACTOR, runId).orElseThrow();
        assertThat(view.status()).isEqualTo("CANCELLED");
        assertThat(view.recoveryCheckpointId()).isEqualTo(checkpointId);
        List<DurableChatRunStore.StoredEvent> events = store.events(ACTOR, runId, 0, 20);
        assertThat(events).extracting(DurableChatRunStore.StoredEvent::eventType)
                .containsExactly("started", "run_recovered", "cancelled");
    }

    @Test
    void forceClaimsRunAfterTotalTimeoutAndFencesTheOldWorker() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        JdbcDurableChatRunStore store = new JdbcDurableChatRunStore(jdbc);
        UUID runId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Instant started = Instant.parse("2026-08-22T12:00:00Z");

        jdbc.sql("""
                        insert into conversation_session
                            (id, workspace_id, owner_user_id, title, status, created_at, updated_at)
                        values (:id, :workspaceId, :userId, 'P2.3 timeout gate',
                                'ACTIVE', :now, :now)
                        """).param("id", sessionId).param("workspaceId", WORKSPACE)
                .param("userId", USER).param("now", Timestamp.from(started)).update();
        jdbc.sql("""
                        insert into agent_run
                            (id, workspace_id, owner_user_id, session_id, trace_id, status,
                             question, started_at, created_at)
                        values (:id, :workspaceId, :userId, :sessionId, :traceId, 'RUNNING',
                                'timeout gate', :now, :now)
                        """).param("id", runId).param("workspaceId", WORKSPACE)
                .param("userId", USER).param("sessionId", sessionId)
                .param("traceId", "p23-timeout-" + runId)
                .param("now", Timestamp.from(started)).update();
        tx(() -> {
            store.enqueue(new DurableChatRunStore.WorkDraft(
                            runId, WORKSPACE, USER, sessionId, "p23-timeout-" + runId,
                            true, "SYSTEM_ADMIN", "question", "context", null, 3, started),
                    "{\"type\":\"started\"}");
            return null;
        });

        DurableChatRunStore.WorkLease first = tx(() -> store.claim(
                "server-a", 1, 3, Duration.ofSeconds(120), started).getFirst());
        DurableChatRunStore.WorkLease timedOut = tx(() -> store.claimTimedOut(
                "timeout-sweeper", 1, Duration.ofSeconds(90),
                Duration.ofSeconds(30), started.plusSeconds(91)).getFirst());

        assertThat(timedOut.runId()).isEqualTo(runId);
        assertThat(timedOut.reclaimed()).isTrue();
        assertThat(timedOut.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThat(tx(() -> store.appendEvent(runId, first.leaseToken(),
                "delta", "{\"content\":\"stale\"}", started.plusSeconds(92)))).isEmpty();
        assertThat(tx(() -> store.markTerminal(
                runId, timedOut.leaseToken(), "FAILED", "TIMED_OUT",
                "", "{\"type\":\"error\"}", started.plusSeconds(92)))).isTrue();
        assertThat(store.findOwned(ACTOR, runId).orElseThrow().status()).isEqualTo("FAILED");
    }

    private static <T> T tx(Supplier<T> action) {
        return transactions.execute(status -> action.get());
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
