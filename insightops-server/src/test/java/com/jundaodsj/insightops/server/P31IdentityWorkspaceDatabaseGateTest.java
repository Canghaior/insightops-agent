package com.jundaodsj.insightops.server;

import com.jundaodsj.insightops.infrastructure.identity.IdentityRepository;
import com.jundaodsj.insightops.infrastructure.identity.WorkspaceRepository;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcAccountWorkspaceStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "INSIGHTOPS_CHAIN_GATE", matches = "true")
class P31IdentityWorkspaceDatabaseGateTest {
    private static final UUID ALPHA = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final String SCHEMA = "p31_gate_" + UUID.randomUUID().toString().replace("-", "");
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
    void persistsActiveWorkspaceSingleUseTokensRateLimitsMfaAndMailLeases() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        JdbcAccountWorkspaceStore accounts = new JdbcAccountWorkspaceStore(jdbc);
        WorkspaceRepository workspaces = new WorkspaceRepository(jdbc);
        IdentityRepository identities = new IdentityRepository(jdbc);
        Instant now = Instant.parse("2026-08-25T00:00:00Z");
        jdbc.sql("insert into user_credential(user_id,password_hash,must_change_password) values (:id,'hash',false)")
                .param("id", OWNER).update();

        UUID team = UUID.randomUUID();
        workspaces.create(team, OWNER, "P3 Team", "p3-team", "isolated", now);
        String sessionHash = "a".repeat(64);
        accounts.saveSession(UUID.randomUUID(), OWNER, ALPHA, sessionHash, "JUnit", "b".repeat(64),
                now, now.plusSeconds(3600));
        assertThat(accounts.findBySessionTokenHash(sessionHash, now).orElseThrow().workspaceId()).isEqualTo(ALPHA);
        assertThat(workspaces.switchSession(sessionHash, OWNER, team, now.plusSeconds(1))).isTrue();
        assertThat(accounts.findBySessionTokenHash(sessionHash, now.plusSeconds(2)).orElseThrow().workspaceId()).isEqualTo(team);

        identities.saveToken(UUID.randomUUID(), OWNER, "EMAIL_VERIFICATION", "c".repeat(64),
                now.plusSeconds(300), now);
        assertThat(identities.consumeToken("c".repeat(64), "EMAIL_VERIFICATION", now.plusSeconds(1)))
                .contains(OWNER);
        assertThat(identities.consumeToken("c".repeat(64), "EMAIL_VERIFICATION", now.plusSeconds(2))).isEmpty();

        IdentityRepository.RateState rate = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            rate = identities.recordRateFailure("LOGIN", "d".repeat(64),
                    now.plusSeconds(attempt), 15, 5, 15);
        }
        assertThat(rate).isNotNull().satisfies(value -> { assertThat(value.failures()).isEqualTo(6); assertThat(value.lockedUntil()).isAfter(now); });

        identities.savePendingMfa(OWNER, "ciphertext", now);
        identities.enableMfa(OWNER, List.of("e".repeat(64)), 100L, now.plusSeconds(1));
        assertThat(identities.claimTotpStep(OWNER, 100L, now.plusSeconds(2))).isFalse();
        assertThat(identities.claimTotpStep(OWNER, 101L, now.plusSeconds(2))).isTrue();
        assertThat(identities.claimTotpStep(OWNER, 101L, now.plusSeconds(2))).isFalse();
        assertThat(identities.consumeRecoveryCode(OWNER, "e".repeat(64), now.plusSeconds(2))).isTrue();
        assertThat(identities.consumeRecoveryCode(OWNER, "e".repeat(64), now.plusSeconds(3))).isFalse();

        UUID mail = UUID.randomUUID();
        identities.enqueueMail(mail, "owner@example.com", "EMAIL_VERIFICATION", "Verify", "ciphertext", now);
        assertThat(identities.claimMail(now.plusSeconds(1), now.minusSeconds(120), 10, "gate"))
                .singleElement().satisfies(task -> assertThat(task.id()).isEqualTo(mail));
        identities.completeMail(mail, now.plusSeconds(2));
        assertThat(jdbc.sql("select status from identity_mail_outbox where id=:id")
                .param("id", mail).query(String.class).single()).isEqualTo("SENT");

        assertThat(workspaces.listForUser(OWNER)).extracting(WorkspaceRepository.WorkspaceRecord::id)
                .contains(ALPHA, team);

        UUID archived = UUID.randomUUID();
        workspaces.create(archived, OWNER, "Archived team", "archived-team", null, now);
        assertThat(workspaces.soleOwnedWorkspaceCount(OWNER)).isEqualTo(3);
        assertThat(workspaces.archive(OWNER, archived, now.plusSeconds(3))).isTrue();
        assertThat(workspaces.soleOwnedWorkspaceCount(OWNER)).isEqualTo(2);

        assertThat(workspaces.removeMember(team, OWNER)).isTrue();
        identities.rehomeSessionsAfterWorkspaceRemoval(OWNER, team, now.plusSeconds(4));
        assertThat(accounts.findBySessionTokenHash(sessionHash, now.plusSeconds(5)).orElseThrow()
                .workspaceId()).isEqualTo(ALPHA);
        assertThat(workspaces.listForUser(OWNER)).extracting(WorkspaceRepository.WorkspaceRecord::id)
                .contains(ALPHA).doesNotContain(team);

        identities.requestDeletion(OWNER, now.plusSeconds(6), now.plusSeconds(7));
        assertThat(identities.completeDueDeletions(now.plusSeconds(8), 10)).isZero();
        assertThat(identities.cancelDeletion(OWNER, now.plusSeconds(8))).isTrue();
        assertThat(identities.findIdentity(OWNER).orElseThrow().deletionScheduledAt()).isNull();
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
