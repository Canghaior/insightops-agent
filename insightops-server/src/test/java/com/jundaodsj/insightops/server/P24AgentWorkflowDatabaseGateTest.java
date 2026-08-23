package com.jundaodsj.insightops.server;

import com.jundaodsj.insightops.agent.application.AgentWorkflowTemplateStore;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcAgentWorkflowTemplateStore;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "INSIGHTOPS_CHAIN_GATE", matches = "true")
class P24AgentWorkflowDatabaseGateTest {

    private static final UUID WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final String SCHEMA = "p24_gate_" + UUID.randomUUID().toString().replace("-", "");
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
        assertThat(result.migrationsExecuted).isEqualTo(36);
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
    void persistsImmutableVersionsAndAtomicallyActivatesOneVersion() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        JdbcAgentWorkflowTemplateStore store = new JdbcAgentWorkflowTemplateStore(jdbc);
        Instant now = Instant.parse("2026-08-23T06:00:00Z");
        String graph = """
                {"reason":"database gate","nodes":[{"id":"search",
                "toolName":"knowledge_hybrid_search",
                "arguments":{"query":"Spring AI","candidateLimit":8},
                "dependsOn":[],"condition":"ALWAYS","required":true}]}
                """;
        AgentWorkflowTemplateStore.WorkflowTemplate created = store.create(
                WORKSPACE, USER, new AgentWorkflowTemplateStore.TemplateDraft(
                        "P2.4 database gate", "immutable graph versions", "TECH_RESEARCH",
                        new AgentWorkflowTemplateStore.VersionDraft(
                                "initial", "Research Spring AI", graph)), now);
        assertThat(created.versions()).singleElement().extracting("version", "status")
                .containsExactly(1, "DRAFT");

        UUID versionOne = created.versions().getFirst().id();
        AgentWorkflowTemplateStore.WorkflowTemplate activeOne = store.activate(
                WORKSPACE, created.id(), versionOne, USER, "reviewed v1", now.plusSeconds(1));
        assertThat(activeOne.activeVersionId()).isEqualTo(versionOne);
        assertThat(activeOne.versions().getFirst().status()).isEqualTo("ACTIVE");

        AgentWorkflowTemplateStore.WorkflowTemplate versioned = store.createVersion(
                WORKSPACE, created.id(), USER,
                new AgentWorkflowTemplateStore.VersionDraft(
                        "refined", "Research Spring AI with upgrade risk", graph),
                now.plusSeconds(2));
        assertThat(versioned.versions()).extracting("version").containsExactly(2, 1);
        UUID versionTwo = versioned.versions().getFirst().id();
        AgentWorkflowTemplateStore.WorkflowTemplate activeTwo = store.activate(
                WORKSPACE, created.id(), versionTwo, USER, "reviewed v2", now.plusSeconds(3));

        assertThat(activeTwo.activeVersionId()).isEqualTo(versionTwo);
        assertThat(activeTwo.versions()).extracting("version", "status")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(2, "ACTIVE"),
                        org.assertj.core.groups.Tuple.tuple(1, "RETIRED"));
        assertThat(jdbc.sql("select count(*) from agent_workflow_activation_audit")
                .query(Integer.class).single()).isEqualTo(2);
        assertThat(store.find(UUID.randomUUID(), created.id())).isEmpty();
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
