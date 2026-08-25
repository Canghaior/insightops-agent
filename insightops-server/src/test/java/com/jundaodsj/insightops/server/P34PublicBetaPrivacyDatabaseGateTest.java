package com.jundaodsj.insightops.server;

import com.jundaodsj.insightops.infrastructure.identity.PersonalDataExportRepository;
import com.jundaodsj.insightops.infrastructure.identity.IdentityRepository;
import com.jundaodsj.insightops.infrastructure.identity.PublicAccountDeletionRepository;
import com.jundaodsj.insightops.infrastructure.identity.PublicBetaRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "INSIGHTOPS_CHAIN_GATE", matches = "true")
class P34PublicBetaPrivacyDatabaseGateTest {
    private static final String SCHEMA = "p34_gate_" + UUID.randomUUID().toString().replace("-", "");
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
        String schemaUrl = databaseUrl + (databaseUrl.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
        dataSource = new DriverManagerDataSource(schemaUrl, username, password);
        var result = Flyway.configure().dataSource(dataSource).schemas(SCHEMA).defaultSchema(SCHEMA)
                .createSchemas(true).locations("classpath:db/migration").load().migrate();
        assertThat(result.migrationsExecuted).isEqualTo(40);
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
    void blocksRunsExportsDataOnceAndPurgesAFreeBetaPersonalAccount() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        PublicBetaRepository registrations = new PublicBetaRepository(jdbc);
        PersonalDataExportRepository exports = new PersonalDataExportRepository(jdbc);
        PublicAccountDeletionRepository deletions = new PublicAccountDeletionRepository(jdbc);
        IdentityRepository identities = new IdentityRepository(jdbc);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        Instant now = Instant.parse("2026-08-26T00:00:00Z");
        UUID userId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        transaction.executeWithoutResult(ignored -> registrations.create(
                command(userId, workspaceId, now), 100));
        UUID sessionId = UUID.randomUUID();
        jdbc.sql("""
                insert into conversation_session
                    (id,workspace_id,title,status,owner_user_id,created_at,updated_at)
                values (:id,:workspaceId,'Privacy test','ACTIVE',:userId,:now,:now)
                """).param("id", sessionId).param("workspaceId", workspaceId)
                .param("userId", userId).param("now", timestamp(now)).update();
        UUID authSessionId = UUID.randomUUID();
        jdbc.sql("""
                insert into auth_session
                    (id,user_id,token_hash,active_workspace_id,created_at,expires_at,last_seen_at)
                values (:id,:userId,:hash,:workspaceId,:now,:expiresAt,:now)
                """).param("id", authSessionId).param("userId", userId).param("hash", "e".repeat(64))
                .param("workspaceId", workspaceId).param("now", timestamp(now))
                .param("expiresAt", timestamp(now.plusSeconds(3600))).update();

        registrations.updateControl(false, false, "maintenance", userId, now);
        assertThatThrownBy(() -> insertRun(jdbc, userId, workspaceId, sessionId, now))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("PUBLIC_BETA_RUNS_DISABLED");
        registrations.updateControl(false, true, null, userId, now.plusSeconds(1));
        UUID runId = insertRun(jdbc, userId, workspaceId, sessionId, now.plusSeconds(2));

        PersonalDataExportRepository.Snapshot snapshot = exports.snapshot(userId);
        assertThat(snapshot.account()).contains("privacy-user", "privacy@example.com");
        assertThat(snapshot.runs()).contains(runId.toString(), "export and delete my data");
        assertThat(snapshot.legalConsents()).contains("TERMS", "2026-08-26",
                "b".repeat(64), "c".repeat(64));
        UUID exportId = UUID.randomUUID();
        exports.createReady(exportId, userId, exportId + ".json.enc", "d".repeat(64),
                now.plusSeconds(3600), now);
        assertThat(exports.consume(exportId, userId, "d".repeat(64), now.plusSeconds(3))).isPresent();
        assertThat(exports.consume(exportId, userId, "d".repeat(64), now.plusSeconds(4))).isEmpty();

        deletions.request(userId, now.plusSeconds(5), now.plusSeconds(6));
        assertThat(deletions.claimDue(now.plusSeconds(7), now.minusSeconds(900), 10))
                .containsExactly(userId);
        assertThat(identities.cancelDeletion(userId, now.plusSeconds(8))).isFalse();
        deletions.complete(userId, now.plusSeconds(8));
        assertThat(jdbc.sql("select status from app_user where id=:id").param("id", userId)
                .query(String.class).single()).isEqualTo("DISABLED");
        assertThat(jdbc.sql("select registration_slot from public_registration where user_id=:id")
                .param("id", userId).query(Integer.class).optional()).isEmpty();
        assertThat(jdbc.sql("select count(*) from workspace where id=:id").param("id", workspaceId)
                .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from agent_run where owner_user_id=:id")
                .param("id", userId).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from conversation_session where owner_user_id=:id")
                .param("id", userId).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from auth_session where user_id=:id")
                .param("id", userId).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("select status from personal_data_export where id=:id")
                .param("id", exportId).query(String.class).single()).isEqualTo("EXPIRED");
        assertThat(jdbc.sql("select purge_status from account_deletion_request where user_id=:id")
                .param("id", userId).query(String.class).single()).isEqualTo("COMPLETED");
    }

    private static UUID insertRun(JdbcClient jdbc, UUID userId, UUID workspaceId,
                                  UUID sessionId, Instant now) {
        UUID runId = UUID.randomUUID();
        jdbc.sql("""
                insert into agent_run
                    (id,workspace_id,session_id,trace_id,status,question,owner_user_id,created_at)
                values (:id,:workspaceId,:sessionId,:traceId,'CREATED',
                        'export and delete my data',:userId,:now)
                """).param("id", runId).param("workspaceId", workspaceId)
                .param("sessionId", sessionId).param("traceId", "p34-" + runId)
                .param("userId", userId).param("now", timestamp(now)).update();
        return runId;
    }

    private static PublicBetaRepository.RegistrationCommand command(UUID userId, UUID workspaceId,
                                                                     Instant now) {
        return new PublicBetaRepository.RegistrationCommand(userId, workspaceId, "privacy-user",
                "Privacy User", "privacy@example.com", "privacy@example.com", "hash",
                "Privacy Workspace", "privacy-" + workspaceId.toString().substring(0, 8),
                "b".repeat(64), "c".repeat(64), List.of(
                new PublicBetaRepository.Consent("TERMS", "2026-08-26"),
                new PublicBetaRepository.Consent("PRIVACY", "2026-08-26"),
                new PublicBetaRepository.Consent("ACCEPTABLE_USE", "2026-08-26"),
                new PublicBetaRepository.Consent("AGE_CONFIRMATION", "14")),
                now.plusSeconds(86_400), now);
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
    private static java.time.OffsetDateTime timestamp(Instant value) {
        return java.time.OffsetDateTime.ofInstant(value, java.time.ZoneOffset.UTC);
    }

}
