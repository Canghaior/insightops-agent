package com.jundaodsj.insightops.server;

import com.jundaodsj.insightops.agent.application.AgentWorkflowProductStore;
import com.jundaodsj.insightops.agent.application.AgentWorkflowTemplateStore;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcAgentWorkflowProductStore;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "INSIGHTOPS_CHAIN_GATE", matches = "true")
class P24CWorkflowProductDatabaseGateTest {

    private static final UUID WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID REVIEWER = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final String SCHEMA = "p24c_gate_" + UUID.randomUUID().toString().replace("-", "");
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
        assertThat(result.migrationsExecuted).isEqualTo(37);
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
    void persistsPresetsRevocableSharesAndRealRunQualitySamples() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        JdbcAgentWorkflowTemplateStore templates = new JdbcAgentWorkflowTemplateStore(jdbc);
        JdbcAgentWorkflowProductStore products = new JdbcAgentWorkflowProductStore(jdbc);
        Instant now = Instant.parse("2026-08-24T10:00:00Z");
        String graph = """
                {"reason":"P2.4-C gate","inputs":{"topic":{"type":"string","required":true}},
                "nodes":[{"id":"search","toolName":"knowledge_hybrid_search",
                "arguments":{"query":"${inputs.topic}","candidateLimit":8},"dependsOn":[],
                "condition":"ALWAYS","required":true,"exposeOutputs":["resultCount"]}]}
                """;
        AgentWorkflowTemplateStore.WorkflowTemplate template = templates.create(
                WORKSPACE, USER, new AgentWorkflowTemplateStore.TemplateDraft(
                        "P2.4-C database gate", "productization", "TECH_RESEARCH",
                        new AgentWorkflowTemplateStore.VersionDraft("v1", "Research ${inputs.topic}", graph)), now);
        UUID versionId = template.versions().getFirst().id();
        templates.activate(WORKSPACE, template.id(), versionId, USER, "gate", now.plusSeconds(1));

        AgentWorkflowProductStore.ParameterPreset first = products.savePreset(
                new AgentWorkflowProductStore.PresetDraft(
                        UUID.randomUUID(), WORKSPACE, USER, template.id(), versionId,
                        "Spring AI", "{\"topic\":\"Spring AI\"}", now.plusSeconds(2)));
        AgentWorkflowProductStore.ParameterPreset updated = products.savePreset(
                new AgentWorkflowProductStore.PresetDraft(
                        UUID.randomUUID(), WORKSPACE, USER, template.id(), versionId,
                        "Spring AI", "{\"topic\":\"Spring AI 2\"}", now.plusSeconds(3)));
        assertThat(updated.id()).isEqualTo(first.id());
        assertThat(products.presets(WORKSPACE, USER, template.id(), versionId))
                .singleElement().extracting(AgentWorkflowProductStore.ParameterPreset::valuesJson)
                .asString().contains("Spring AI 2");

        UUID shareId = UUID.randomUUID();
        products.createShare(new AgentWorkflowProductStore.ShareDraft(
                shareId, WORKSPACE, template.id(), versionId, "a".repeat(64),
                now.plusSeconds(3_600), USER, now.plusSeconds(4)));
        assertThat(products.findActiveShare("a".repeat(64), now.plusSeconds(5))).isPresent();
        products.recordImport(shareId, now.plusSeconds(6));
        assertThat(products.shares(WORKSPACE, template.id()).getFirst().importCount()).isEqualTo(1);
        assertThat(products.revokeShare(WORKSPACE, shareId, now.plusSeconds(7))).isTrue();
        assertThat(products.findActiveShare("a".repeat(64), now.plusSeconds(8))).isEmpty();

        UUID runId = UUID.randomUUID();
        jdbc.sql("""
                        insert into app_user (id, username, display_name, status)
                        values (:id, 'p24c-reviewer', 'P2.4-C Reviewer', 'ACTIVE')
                        """).param("id", REVIEWER).update();
        jdbc.sql("""
                        insert into agent_run (
                            id, workspace_id, owner_user_id, trace_id, status, question, answer,
                            prompt_tokens, completion_tokens, estimated_cost_cny,
                            started_at, finished_at, created_at
                        ) values (
                            :id, :workspaceId, :ownerUserId, :traceId, 'SUCCEEDED', 'question', 'answer',
                            100, 50, 0.012345, :startedAt, :finishedAt, :createdAt
                        )
                        """)
                .param("id", runId).param("workspaceId", WORKSPACE).param("ownerUserId", USER)
                .param("traceId", "p24c-" + runId).param("startedAt", Timestamp.from(now.plusSeconds(10)))
                .param("finishedAt", Timestamp.from(now.plusSeconds(15))).param("createdAt", Timestamp.from(now.plusSeconds(9))).update();
        jdbc.sql("""
                        insert into agent_workflow_run (
                            run_id, workspace_id, owner_user_id, template_id, template_version_id,
                            template_name_snapshot, template_version_snapshot, entry_question_snapshot,
                            graph_spec_snapshot, input_snapshot, tool_contract_fingerprint,
                            request_id, retry_root_run_id, created_at
                        ) values (
                            :runId, :workspaceId, :ownerUserId, :templateId, :versionId,
                            'P2.4-C database gate', 1, 'Research Spring AI', cast(:graph as jsonb),
                            '{"topic":"Spring AI"}', :fingerprint, :requestId, :runId, :createdAt
                        )
                        """)
                .param("runId", runId).param("workspaceId", WORKSPACE).param("ownerUserId", USER)
                .param("templateId", template.id()).param("versionId", versionId).param("graph", graph)
                .param("fingerprint", "b".repeat(64)).param("requestId", UUID.randomUUID())
                .param("createdAt", Timestamp.from(now.plusSeconds(9))).update();
        jdbc.sql("""
                        insert into agent_workflow_run_node (
                            id, run_id, logical_node_id, tool_name, tool_version, risk_level,
                            required, condition_type, dependency_node_ids, argument_template,
                            expose_outputs, status, input_tokens, output_tokens,
                            estimated_cost_cny, created_at, updated_at
                        ) values (
                            :id, :runId, 'search', 'knowledge_hybrid_search', 1, 'READ_ONLY',
                            true, 'ALWAYS', '[]', '{}', '["resultCount"]', 'SUCCEEDED',
                            20, 10, 0.001000, :createdAt, :createdAt
                        )
                        """)
                .param("id", UUID.randomUUID()).param("runId", runId)
                .param("createdAt", Timestamp.from(now.plusSeconds(10))).update();
        jdbc.sql("""
                        insert into research_answer_feedback (
                            id, user_id, workspace_id, run_id, helpful, review_status
                        ) values (:id, :userId, :workspaceId, :runId, true, 'PENDING')
                        """)
                .param("id", UUID.randomUUID()).param("userId", USER)
                .param("workspaceId", WORKSPACE).param("runId", runId).update();
        jdbc.sql("""
                        insert into research_answer_feedback (
                            id, user_id, workspace_id, run_id, helpful, review_status
                        ) values (:id, :userId, :workspaceId, :runId, false, 'PENDING')
                        """)
                .param("id", UUID.randomUUID()).param("userId", REVIEWER)
                .param("workspaceId", WORKSPACE).param("runId", runId).update();
        jdbc.sql("""
                        insert into research_citation_feedback (
                            id, user_id, workspace_id, run_id, citation_url, correct, review_status
                        ) values (:id, :userId, :workspaceId, :runId,
                                  'https://spring.io/projects/spring-ai', true, 'PENDING')
                        """)
                .param("id", UUID.randomUUID()).param("userId", USER)
                .param("workspaceId", WORKSPACE).param("runId", runId).update();

        assertThat(products.runMetrics(WORKSPACE, template.id(), now, 100))
                .singleElement().satisfies(metric -> {
                    assertThat(metric.status()).isEqualTo("SUCCEEDED");
                    assertThat(metric.totalTokens()).isEqualTo(150);
                    assertThat(metric.helpful()).isTrue();
                    assertThat(metric.feedbackCount()).isEqualTo(2);
                    assertThat(metric.helpfulCount()).isEqualTo(1);
                    assertThat(metric.correctCitationCount()).isEqualTo(1);
                    assertThat(metric.successfulNodeCount()).isEqualTo(1);
                });
        assertThat(products.deletePreset(WORKSPACE, USER, first.id())).isTrue();
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
