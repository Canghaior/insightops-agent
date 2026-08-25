package com.jundaodsj.insightops.server;

import com.jundaodsj.insightops.infrastructure.identity.IdentityRepository;
import com.jundaodsj.insightops.infrastructure.identity.PublicBetaActivationRepository;
import com.jundaodsj.insightops.infrastructure.identity.PublicBetaRepository;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "INSIGHTOPS_CHAIN_GATE", matches = "true")
class P32PublicBetaDatabaseGateTest {
    private static final String SCHEMA = "p32_gate_" + UUID.randomUUID().toString().replace("-", "");
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
    void reservesAUniqueSlotActivatesConsumedVerificationAndPersistsOneRunLimit() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        PublicBetaRepository registrations = new PublicBetaRepository(jdbc);
        IdentityRepository identities = new IdentityRepository(jdbc);
        PublicBetaActivationRepository activation = new PublicBetaActivationRepository(jdbc);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        Instant now = Instant.parse("2026-08-26T00:00:00Z");
        UUID userId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        PublicBetaRepository.Registration created = transaction.execute(ignored -> registrations.create(
                command(userId, workspaceId, "beta-one", "one@example.com", now), 100));
        assertThat(created).isNotNull();
        assertThat(created.slot()).isEqualTo(1);
        assertThat(registrations.counts().occupied()).isEqualTo(1);
        assertThat(jdbc.sql("select max_concurrent_runs from agent_cost_policy where workspace_id=:id")
                .param("id", workspaceId).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select hard_limit_enabled from agent_cost_policy where workspace_id=:id")
                .param("id", workspaceId).query(Boolean.class).single()).isTrue();

        identities.saveToken(UUID.randomUUID(), userId, "EMAIL_VERIFICATION", "a".repeat(64),
                now.plusSeconds(3600), now);
        assertThat(identities.consumeToken("a".repeat(64), "EMAIL_VERIFICATION", now.plusSeconds(1)))
                .contains(userId);
        assertThat(activation.activateVerifiedRegistrations(now.plusSeconds(2))).isEqualTo(1);
        assertThat(jdbc.sql("select status from app_user where id=:id").param("id", userId)
                .query(String.class).single()).isEqualTo("ACTIVE");
        assertThat(registrations.registration(userId).orElseThrow().status()).isEqualTo("ACTIVE");

        registrations.updateControl(false, false, "maintenance", userId, now.plusSeconds(3));
        assertThat(registrations.publicRunsEnabled(workspaceId)).isFalse();

        UUID expiredUserId = UUID.randomUUID();
        UUID expiredWorkspaceId = UUID.randomUUID();
        transaction.executeWithoutResult(ignored -> registrations.create(command(expiredUserId,
                expiredWorkspaceId, "retry-user", "retry@example.com", now), 100));
        Integer expiredCount = transaction.execute(
                ignored -> registrations.expirePending(now.plusSeconds(86_401)));
        assertThat(expiredCount).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from app_user where id=:id").param("id", expiredUserId)
                .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from workspace where id=:id").param("id", expiredWorkspaceId)
                .query(Integer.class).single()).isZero();

        UUID retryUserId = UUID.randomUUID();
        UUID retryWorkspaceId = UUID.randomUUID();
        PublicBetaRepository.Registration retried = transaction.execute(ignored -> registrations.create(
                command(retryUserId, retryWorkspaceId, "retry-user", "retry@example.com",
                        now.plusSeconds(86_402)), 100));
        assertThat(retried).isNotNull();
        assertThat(retried.slot()).isEqualTo(2);
    }

    private static PublicBetaRepository.RegistrationCommand command(UUID userId, UUID workspaceId,
                                                                     String username, String email,
                                                                     Instant now) {
        return new PublicBetaRepository.RegistrationCommand(userId, workspaceId, username, "Beta User",
                email, email, "hash", "Beta Workspace", "beta-" + workspaceId.toString().substring(0, 8),
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
}
