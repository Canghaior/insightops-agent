package com.jundaodsj.insightops.server;

import com.jundaodsj.insightops.agent.application.AgentWorkflowRunStore;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcAgentWorkflowRunStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "INSIGHTOPS_CHAIN_GATE", matches = "true")
class P24BWorkflowRunDatabaseGateTest {

    private static final UUID WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final String SCHEMA = "p24b_gate_" + UUID.randomUUID().toString().replace("-", "");
    private static String databaseUrl;
    private static String username;
    private static String password;
    private static DataSource dataSource;

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
        assertThat(result.migrationsExecuted).isEqualTo(38);
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
    void snapshotsNodesAndFencesEveryAttemptWriteByCurrentLease() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        JdbcAgentWorkflowRunStore store = new JdbcAgentWorkflowRunStore(jdbc);
        UUID sessionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID firstLease = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-23T14:00:00Z");
        jdbc.sql("""
                        insert into conversation_session
                            (id, workspace_id, title, status, owner_user_id, created_at, updated_at)
                        values (:id, :workspaceId, 'P2.4-B gate', 'ACTIVE', :userId, :now, :now)
                        """)
                .param("id", sessionId).param("workspaceId", WORKSPACE).param("userId", USER)
                .param("now", java.sql.Timestamp.from(now)).update();
        jdbc.sql("""
                        insert into agent_run
                            (id, workspace_id, session_id, trace_id, status, question,
                             owner_user_id, started_at, created_at)
                        values (:id, :workspaceId, :sessionId, :traceId, 'RUNNING',
                                'workflow gate', :userId, :now, :now)
                        """)
                .param("id", runId).param("workspaceId", WORKSPACE).param("sessionId", sessionId)
                .param("traceId", "p24b-" + runId).param("userId", USER)
                .param("now", java.sql.Timestamp.from(now)).update();
        jdbc.sql("""
                        insert into agent_run_work (
                            run_id, workspace_id, owner_user_id, session_id, trace_id,
                            status, access_level, user_prompt, contextual_prompt,
                            attempt_count, max_attempts, claimed_by, lease_token,
                            heartbeat_at, lease_expires_at, created_at, updated_at
                        ) values (
                            :runId, :workspaceId, :userId, :sessionId, :traceId,
                            'RUNNING', 'WORKSPACE_OWNER', 'workflow gate', 'workflow gate',
                            1, 3, 'worker-a', :leaseToken, :now, :expiresAt, :now, :now
                        )
                        """)
                .param("runId", runId).param("workspaceId", WORKSPACE).param("userId", USER)
                .param("sessionId", sessionId).param("traceId", "p24b-" + runId)
                .param("leaseToken", firstLease).param("now", java.sql.Timestamp.from(now))
                .param("expiresAt", java.sql.Timestamp.from(now.plusSeconds(60))).update();

        UUID workflowNodeId = UUID.randomUUID();
        store.create(new AgentWorkflowRunStore.WorkflowRunDraft(
                        runId, WORKSPACE, USER, null, null, "Snapshot template", 2,
                        "Research ${inputs.topic}",
                        "{\"inputs\":{\"topic\":{\"type\":\"string\",\"required\":true}},\"nodes\":[]}",
                        "{\"topic\":\"Spring AI\"}", "a".repeat(64), UUID.randomUUID(),
                        null, runId, null, now),
                List.of(new AgentWorkflowRunStore.NodeDraft(
                        workflowNodeId, "research", "knowledge_hybrid_search", 1,
                        "READ_ONLY", true, "ALWAYS", "[]",
                        "{\"query\":\"${inputs.topic}\",\"candidateLimit\":8}",
                        "[\"resultCount\"]", "PENDING", null, null, null, null,
                        null, "[]", "[]", 0, 0, 0, BigDecimal.ZERO,
                        null, null, null, now)));

        AgentWorkflowRunStore.NodeAttempt first = store.beginNode(
                runId, "research", firstLease, 1, "worker-a",
                "{\"query\":\"Spring AI\",\"candidateLimit\":8}", now.plusSeconds(1));
        UUID secondLease = UUID.randomUUID();
        jdbc.sql("""
                        update agent_run_work set lease_token = :leaseToken,
                            claimed_by = 'worker-b', attempt_count = 2,
                            lease_expires_at = :expiresAt where run_id = :runId
                        """)
                .param("leaseToken", secondLease)
                .param("expiresAt", java.sql.Timestamp.from(now.plusSeconds(120)))
                .param("runId", runId).update();
        assertThatThrownBy(() -> store.finishNode(
                runId, "research", firstLease, first.id(), "SUCCEEDED", null, null,
                "{\"resultCount\":1}", "{\"resultCount\":1}", "evidence",
                "[]", "[]", 0, 0, BigDecimal.ZERO, null, now.plusSeconds(2)))
                .hasMessageContaining("AGENT_RUN_LEASE_LOST");

        AgentWorkflowRunStore.NodeAttempt second = store.beginNode(
                runId, "research", secondLease, 2, "worker-b",
                "{\"query\":\"Spring AI\",\"candidateLimit\":8}", now.plusSeconds(3));
        store.finishNode(
                runId, "research", secondLease, second.id(), "SUCCEEDED", null, null,
                "{\"resultCount\":1}", "{\"resultCount\":1}", "evidence",
                "[]", "[]", 0, 0, BigDecimal.ZERO, null, now.plusSeconds(4));

        AgentWorkflowRunStore.WorkflowRun stored = store.find(runId).orElseThrow();
        assertThat(stored.templateVersion()).isEqualTo(2);
        assertThat(stored.inputJson()).contains("Spring AI");
        assertThat(stored.nodes()).singleElement().satisfies(node -> {
            assertThat(node.status()).isEqualTo("SUCCEEDED");
            assertThat(node.attemptCount()).isEqualTo(2);
            assertThat(node.attempts()).hasSize(2);
            assertThat(node.attempts()).extracting(
                    AgentWorkflowRunStore.NodeAttemptView::status)
                    .containsExactly("FAILED", "SUCCEEDED");
            assertThat(node.attempts().getFirst().errorCode()).isEqualTo("RUN_RECOVERED");
            assertThat(node.exposedOutputJson()).contains("resultCount");
        });
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
