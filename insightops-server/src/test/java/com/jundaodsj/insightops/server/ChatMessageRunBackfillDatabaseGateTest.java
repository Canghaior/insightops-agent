package com.jundaodsj.insightops.server;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the actual V41 -> V42 upgrade against historical rows, not only a fresh schema. */
@EnabledIfEnvironmentVariable(named = "INSIGHTOPS_CHAIN_GATE", matches = "true")
class ChatMessageRunBackfillDatabaseGateTest {
    private static final String SCHEMA = "chat_backfill_gate_" + UUID.randomUUID().toString().replace("-", "");
    private static final UUID WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final Instant FINISHED = Instant.parse("2026-11-09T02:00:00.123456Z");
    private static final Map<String, UUID> MESSAGES = new LinkedHashMap<>();
    private static DriverManagerDataSource root;
    private static JdbcClient jdbc;
    private static UUID uniqueRun;

    @BeforeAll
    static void migrateHistoricalData() {
        String url = environment("DB_URL", "jdbc:postgresql://localhost:55432/insightops");
        String username = environment("DB_USERNAME", "insightops");
        String password = environment("DB_PASSWORD", "insightops_dev");
        root = new DriverManagerDataSource(url, username, password);
        JdbcClient.create(root).sql("create extension if not exists vector with schema public").update();
        var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?")
                + "currentSchema=" + SCHEMA, username, password);
        Flyway.configure().dataSource(source).schemas(SCHEMA).defaultSchema(SCHEMA).createSchemas(true)
                .locations("classpath:db/migration").target("41").load().migrate();
        jdbc = JdbcClient.create(source);
        seedHistoricalMessages();

        var upgrade = Flyway.configure().dataSource(source).schemas(SCHEMA).defaultSchema(SCHEMA)
                .locations("classpath:db/migration").target("42").load().migrate();
        assertThat(upgrade.migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.sql("select version from flyway_schema_history where success order by installed_rank desc limit 1")
                .query(String.class).single()).isEqualTo("42");
    }

    @AfterAll
    static void cleanup() {
        if (root != null) JdbcClient.create(root).sql("drop schema if exists " + SCHEMA + " cascade").update();
    }

    @Test
    void linksOnlyTheUniqueExactHistoricalAnswer() {
        assertThat(linkedRun("unique")).isEqualTo(uniqueRun);
    }

    @Test
    void leavesBothDirectionsOfAmbiguousMatchesUnlinked() {
        assertUnlinked("two-runs-one-message", "two-messages-first", "two-messages-second");
    }

    @Test
    void neverLinksAcrossOwnerWorkspaceOrSessionBoundaries() {
        assertUnlinked("other-owner", "other-workspace", "other-session");
    }

    @Test
    void doesNotGuessFromAnswerTextOrLinkUserMessagesAndFailedRuns() {
        assertUnlinked("different-time", "user-message", "failed-run");
    }

    private static void seedHistoricalMessages() {
        UUID uniqueSession = session();
        message("unique", uniqueSession, "ASSISTANT", "unique answer", 1);
        uniqueRun = run(uniqueSession, WORKSPACE, OWNER, "unique answer", FINISHED, "SUCCEEDED");

        UUID multipleRuns = session();
        message("two-runs-one-message", multipleRuns, "ASSISTANT", "identical answer", 1);
        run(multipleRuns, WORKSPACE, OWNER, "identical answer", FINISHED, "SUCCEEDED");
        run(multipleRuns, WORKSPACE, OWNER, "identical answer", FINISHED, "SUCCEEDED");

        UUID multipleMessages = session();
        message("two-messages-first", multipleMessages, "ASSISTANT", "duplicate message", 1);
        message("two-messages-second", multipleMessages, "ASSISTANT", "duplicate message", 2);
        run(multipleMessages, WORKSPACE, OWNER, "duplicate message", FINISHED, "SUCCEEDED");

        UUID otherOwner = UUID.randomUUID(), otherWorkspace = UUID.randomUUID();
        jdbc.sql("insert into app_user(id,username,display_name) values(:id,:name,'Other owner')")
                .param("id", otherOwner).param("name", "backfill-" + otherOwner).update();
        jdbc.sql("insert into workspace(id,name,slug) values(:id,'Other workspace',:slug)")
                .param("id", otherWorkspace).param("slug", "backfill-" + otherWorkspace).update();

        // Deliberately inconsistent historical ownership is legal under the old single-column FKs.
        UUID ownerMismatch = session();
        message("other-owner", ownerMismatch, "ASSISTANT", "owner boundary", 1);
        run(ownerMismatch, WORKSPACE, otherOwner, "owner boundary", FINISHED, "SUCCEEDED");
        UUID workspaceMismatch = session();
        message("other-workspace", workspaceMismatch, "ASSISTANT", "workspace boundary", 1);
        run(workspaceMismatch, otherWorkspace, OWNER, "workspace boundary", FINISHED, "SUCCEEDED");
        UUID sessionMismatch = session();
        message("other-session", sessionMismatch, "ASSISTANT", "session boundary", 1);
        run(session(), WORKSPACE, OWNER, "session boundary", FINISHED, "SUCCEEDED");

        UUID differentTime = session();
        message("different-time", differentTime, "ASSISTANT", "same text at another time", 1);
        run(differentTime, WORKSPACE, OWNER, "same text at another time", FINISHED.plusSeconds(1), "SUCCEEDED");
        UUID userMessage = session();
        message("user-message", userMessage, "USER", "not an assistant answer", 1);
        run(userMessage, WORKSPACE, OWNER, "not an assistant answer", FINISHED, "SUCCEEDED");
        UUID failedRun = session();
        message("failed-run", failedRun, "ASSISTANT", "failed answer", 1);
        run(failedRun, WORKSPACE, OWNER, "failed answer", FINISHED, "FAILED");
    }

    private static UUID session() {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into conversation_session(id,workspace_id,owner_user_id,title) values(:id,:workspace,:owner,'Historical conversation')")
                .param("id", id).param("workspace", WORKSPACE).param("owner", OWNER).update();
        return id;
    }

    private static void message(String key, UUID sessionId, String role, String content, int sequence) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into conversation_message(id,session_id,role,content,sequence_no,created_at)
                values(:id,:session,:role,:content,:sequence,:finished)
                """).param("id", id).param("session", sessionId).param("role", role)
                .param("content", content).param("sequence", sequence).param("finished", Timestamp.from(FINISHED)).update();
        MESSAGES.put(key, id);
    }

    private static UUID run(UUID sessionId, UUID workspaceId, UUID ownerId, String answer, Instant finished, String status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into agent_run(id,workspace_id,owner_user_id,session_id,trace_id,status,question,answer,finished_at)
                values(:id,:workspace,:owner,:session,:trace,:status,'Historical question',:answer,:finished)
                """).param("id", id).param("workspace", workspaceId).param("owner", ownerId)
                .param("session", sessionId).param("trace", "backfill-" + id).param("status", status)
                .param("answer", answer).param("finished", Timestamp.from(finished)).update();
        return id;
    }

    private static UUID linkedRun(String key) {
        return jdbc.sql("select run_id from conversation_message where id=:id")
                .param("id", MESSAGES.get(key))
                .query((row, index) -> new MessageLink(row.getObject("run_id", UUID.class))).single().runId();
    }

    private static void assertUnlinked(String... keys) {
        for (String key : keys) assertThat(linkedRun(key)).as(key).isNull();
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private record MessageLink(UUID runId) { }
}
