package com.jundaodsj.insightops.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.agent.application.AgentRunQuery;
import com.jundaodsj.insightops.conversation.application.ChatRunStore;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import com.jundaodsj.insightops.identity.application.AdminAccountStore;
import com.jundaodsj.insightops.infrastructure.config.DeepSeekModelProperties;
import com.jundaodsj.insightops.infrastructure.config.DeepSeekPricingProperties;
import com.jundaodsj.insightops.infrastructure.model.DeepSeekCostEstimator;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcAgentRunQuery;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcAdminAccountStore;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcAccountWorkspaceStore;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcAgentToolExecutionStore;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcChatRunStore;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcUserMemoryStore;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcConversationManager;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcUserProjectWatchStore;
import com.jundaodsj.insightops.model.application.ChatStreamEvent;
import com.jundaodsj.insightops.model.application.ChatStreamSession;
import com.jundaodsj.insightops.model.application.ModelUsage;
import com.jundaodsj.insightops.server.api.ChatStreamController;
import com.jundaodsj.insightops.server.api.TraceIdFilter;
import com.jundaodsj.insightops.server.chat.ChatStreamSessionRegistry;
import com.jundaodsj.insightops.server.chat.GitHubReleaseEvidenceFormatter;
import com.jundaodsj.insightops.server.chat.P0ChatGuardrail;
import com.jundaodsj.insightops.server.chat.ReleaseQuestionRouter;
import com.jundaodsj.insightops.server.chat.ReleaseToolService;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import com.jundaodsj.insightops.tool.application.github.GitHubRelease;
import com.jundaodsj.insightops.tool.application.github.GitHubReleaseResult;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "INSIGHTOPS_CHAIN_GATE", matches = "true")
class P0ChainDatabaseGateTest {

    private static final ActorContext ACTOR = new ActorContext(
            UUID.fromString("00000000-0000-0000-0000-000000000101"),
            UUID.fromString("00000000-0000-0000-0000-000000000001"));

    private static final String SCHEMA = "chain_gate_" + UUID.randomUUID().toString().replace("-", "");
    private static String databaseUrl;
    private static String username;
    private static String password;
    private static DataSource dataSource;
    private static JdbcChatRunStore runStore;
    private static AgentRunQuery runQuery;
    private static ReleaseToolService releaseToolService;
    private static JdbcUserMemoryStore memoryStore;
    private static JdbcConversationManager conversationManager;
    private static JdbcUserProjectWatchStore projectWatchStore;
    private static JdbcClient jdbcClient;
    private static JdbcAdminAccountStore adminAccountStore;

    @BeforeAll
    static void prepareIsolatedSchema() {
        databaseUrl = environment("DB_URL", "jdbc:postgresql://localhost:55432/insightops");
        username = environment("DB_USERNAME", "insightops");
        password = environment("DB_PASSWORD", "insightops_dev");
        String schemaUrl = databaseUrl + (databaseUrl.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
        dataSource = new DriverManagerDataSource(schemaUrl, username, password);

        var migration = Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .createSchemas(true)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        assertThat(migration.migrationsExecuted).isEqualTo(9);

        jdbcClient = JdbcClient.create(dataSource);
        adminAccountStore = new JdbcAdminAccountStore(jdbcClient);
        DeepSeekCostEstimator estimator = new DeepSeekCostEstimator(pricing());
        runStore = new JdbcChatRunStore(jdbcClient, estimator);
        runQuery = new JdbcAgentRunQuery(jdbcClient, objectMapper());
        memoryStore = new JdbcUserMemoryStore(jdbcClient);
        conversationManager = new JdbcConversationManager(jdbcClient);
        projectWatchStore = new JdbcUserProjectWatchStore(jdbcClient);
        releaseToolService = new ReleaseToolService(
                new ReleaseQuestionRouter(),
                query -> new GitHubReleaseResult(List.of(new GitHubRelease(
                        "spring-ai",
                        "Spring AI",
                        "v2.0.0",
                        "Spring AI 2.0.0",
                        Instant.parse("2026-06-12T12:00:00Z"),
                        "https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0",
                        false,
                        "Automated chain gate release evidence.")), Instant.parse("2026-08-16T00:00:00Z")),
                new JdbcAgentToolExecutionStore(jdbcClient),
                new GitHubReleaseEvidenceFormatter(),
                objectMapper());
    }

    @Test
    void shouldPersistAccountAdministrationAndAuditRecords() {
        JdbcAccountWorkspaceStore accountStore = new JdbcAccountWorkspaceStore(jdbcClient);
        accountStore.ensureBootstrapCredential("configured-admin", "Configured Admin", "bootstrap-hash");
        accountStore.ensureBootstrapCredential("configured-admin", "Renamed Admin", "must-not-overwrite");
        assertThat(jdbcClient.sql("""
                select u.system_role || ':' || member.role || ':' || credential.password_hash
                from app_user u
                join workspace_member member on member.user_id = u.id
                join user_credential credential on credential.user_id = u.id
                where u.username = 'configured-admin'
                """).query(String.class).single())
                .isEqualTo("SYSTEM_ADMIN:OWNER:bootstrap-hash");

        UUID userId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-17T08:00:00Z");
        AdminAccountStore.ManagedUser created = adminAccountStore.createUser(
                userId, ACTOR.workspaceId(), "chain-member", "Chain Member", "$2a$12$test",
                "USER", "MEMBER", now);

        assertThat(created.mustChangePassword()).isTrue();
        assertThat(adminAccountStore.listUsers(ACTOR.workspaceId()))
                .extracting(AdminAccountStore.ManagedUser::username)
                .contains("chain-member");

        assertThat(adminAccountStore.updateWorkspaceRole(ACTOR.workspaceId(), userId, "OWNER", now.plusSeconds(1)))
                .get().extracting(AdminAccountStore.ManagedUser::workspaceRole).isEqualTo("OWNER");
        assertThat(adminAccountStore.resetPassword(
                ACTOR.workspaceId(), userId, "$2a$12$reset", now.plusSeconds(2))).isTrue();

        adminAccountStore.appendAudit(UUID.randomUUID(), ACTOR.workspaceId(), ACTOR.userId(), userId,
                "PASSWORD_RESET", "{\"mustChangePassword\":true}", now.plusSeconds(3));
        assertThat(adminAccountStore.listAudit(ACTOR.workspaceId(), 10))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.action()).isEqualTo("PASSWORD_RESET");
                    assertThat(entry.targetUsername()).isEqualTo("chain-member");
                });

        assertThat(adminAccountStore.updateStatus(
                ACTOR.workspaceId(), userId, "DISABLED", now.plusSeconds(4)))
                .get().extracting(AdminAccountStore.ManagedUser::status).isEqualTo("DISABLED");
    }

    @AfterAll
    static void removeIsolatedSchema() throws Exception {
        DriverManagerDataSource admin = new DriverManagerDataSource(databaseUrl, username, password);
        try (Connection connection = admin.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("drop schema \"" + SCHEMA + "\" cascade");
        }
    }

    @Test
    void shouldPersistAndReadTheCompleteToolAugmentedChain() {
        String traceId = "chain-success-" + UUID.randomUUID();
        ChatStreamController controller = controller((request, listener) -> {
            assertThat(request.systemPrompt())
                    .contains("github_release_list", "v2.0.0", "https://github.com/");
            listener.onEvent(ChatStreamEvent.delta("Spring AI v2.0.0："));
            listener.onEvent(ChatStreamEvent.delta(
                    "https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0"));
            listener.onEvent(ChatStreamEvent.completed(
                    "deepseek",
                    "deepseek-v4-flash",
                    new ModelUsage(1_000, 200, 1_200, 100L, 0L),
                    Duration.ofMillis(800),
                    Duration.ofMillis(120)));
            return session(new AtomicBoolean());
        });

        controller.stream(
                new ChatStreamController.ChatStreamRequest("Spring AI 最新正式版本是什么？"),
                request(traceId));

        AgentRunQuery.RunSummary summary = runQuery.listRuns(ACTOR, 0, 20, "SUCCEEDED").items().stream()
                .filter(run -> traceId.equals(run.traceId()))
                .findFirst()
                .orElseThrow();
        AgentRunQuery.RunDetail detail = runQuery.findRun(ACTOR, summary.id()).orElseThrow();

        assertThat(detail.status()).isEqualTo("SUCCEEDED");
        assertThat(detail.answer()).contains("v2.0.0", "https://github.com/");
        assertThat(detail.modelProvider()).isEqualTo("deepseek");
        assertThat(detail.modelName()).isEqualTo("deepseek-v4-flash");
        assertThat(detail.promptTokens()).isEqualTo(1_000);
        assertThat(detail.completionTokens()).isEqualTo(200);
        assertThat(detail.estimatedCostCny()).isEqualByComparingTo("0.001312");
        assertThat(detail.pricingEffectiveDate()).isEqualTo(LocalDate.parse("2026-08-16"));
        assertThat(detail.toolRounds()).isEqualTo(1);
        assertThat(detail.sources()).containsExactly(
                "https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0");
        assertThat(detail.steps()).singleElement().satisfies(step -> {
            assertThat(step.status()).isEqualTo("SUCCEEDED");
            assertThat(step.inputPayload()).isInstanceOf(java.util.Map.class);
            java.util.Map<?, ?> payload = (java.util.Map<?, ?>) step.inputPayload();
            assertThat(payload.get("projectIds")).isEqualTo(List.of("spring-ai"));
            assertThat(payload.get("maxReleasesPerProject")).isEqualTo(2);
            assertThat(payload.get("includePrereleases")).isEqualTo(false);
        });
        assertThat(detail.toolCalls()).singleElement().satisfies(tool -> {
            assertThat(tool.toolName()).isEqualTo("github_release_list");
            assertThat(tool.status()).isEqualTo("SUCCEEDED");
            assertThat(tool.resultPayload().toString()).contains("v2.0.0");
        });
        assertThat(detail.toString()).doesNotContain("DEEPSEEK_API_KEY", "Authorization", "sk-");
        assertThat(runStore.recentMessages(ACTOR, detail.sessionId(), 12))
                .extracting(ChatRunStore.StoredMessage::role)
                .containsExactly("USER", "ASSISTANT");
        ChatRunStore.SessionHistory history = runStore.sessionHistory(ACTOR, detail.sessionId(), 100)
                .orElseThrow();
        assertThat(history.hasEarlierMessages()).isFalse();
        assertThat(history.messages())
                .extracting(ChatRunStore.HistoryMessage::role)
                .containsExactly("USER", "ASSISTANT");
        assertThat(history.messages())
                .extracting(ChatRunStore.HistoryMessage::sequenceNo)
                .containsExactly(1, 2);
    }

    @Test
    void shouldAuditUserCancellationAndTimeoutCode() {
        AtomicBoolean providerCancelled = new AtomicBoolean();
        String cancelTrace = "chain-cancel-" + UUID.randomUUID();
        ChatStreamController controller = controller((request, listener) -> session(providerCancelled));

        controller.stream(new ChatStreamController.ChatStreamRequest("生成一份长报告"), request(cancelTrace));
        AgentRunQuery.RunSummary running = runQuery.listRuns(ACTOR, 0, 20, "RUNNING").items().stream()
                .filter(run -> cancelTrace.equals(run.traceId()))
                .findFirst()
                .orElseThrow();
        var cancelResponse = controller.cancel(running.id().toString(), request("cancel-request"));

        assertThat(cancelResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(providerCancelled).isTrue();
        assertThat(runQuery.findRun(ACTOR, running.id()).orElseThrow().status()).isEqualTo("CANCELLED");

        UUID timeoutRunId = UUID.randomUUID();
        runStore.startRun(
                ACTOR,
                timeoutRunId,
                null,
                "chain-timeout-" + UUID.randomUUID(),
                "模拟超过 90 秒的 Run",
                Instant.now());
        runStore.failRun(timeoutRunId, "", "TIMED_OUT", Instant.now());
        AgentRunQuery.RunDetail timedOut = runQuery.findRun(ACTOR, timeoutRunId).orElseThrow();
        assertThat(timedOut.status()).isEqualTo("FAILED");
        assertThat(timedOut.failureCode()).isEqualTo("TIMED_OUT");
        assertThat(timedOut.steps()).isEmpty();
        assertThat(timedOut.toolCalls()).isEmpty();
    }

    @Test
    void shouldIsolateConversationRunMemoryAndProjectWatchForThreeUsers() {
        ActorContext developer = actor("00000000-0000-0000-0000-000000000102");
        ActorContext architect = actor("00000000-0000-0000-0000-000000000103");
        createTestUser(developer.userId(), "alpha-developer", "Alpha Developer");
        createTestUser(architect.userId(), "alpha-architect", "Alpha Architect");

        UUID developerRun = UUID.randomUUID();
        UUID developerSession = runStore.startRun(
                developer, developerRun, null, "isolation-" + UUID.randomUUID(),
                "developer private question", Instant.now());
        runStore.succeedRun(
                developerRun, "developer private answer", "deepseek", "deepseek-v4-flash",
                new ModelUsage(10, 5, 15, 0L, 0L), List.of(), Instant.now());

        assertThat(runQuery.findRun(developer, developerRun)).isPresent();
        assertThat(runQuery.findRun(architect, developerRun)).isEmpty();
        assertThat(runQuery.findRun(ACTOR, developerRun)).isEmpty();
        assertThat(runStore.sessionHistory(developer, developerSession, 100)).isPresent();
        assertThat(runStore.sessionHistory(architect, developerSession, 100)).isEmpty();
        assertThat(runStore.ownsRun(architect, developerRun)).isFalse();
        assertThat(conversationManager.list(developer, true)).extracting("id").contains(developerSession);
        assertThat(conversationManager.list(architect, true)).isEmpty();

        memoryStore.create(
                developer, UUID.randomUUID(), "answer-style", "conclusion first",
                "PREFERENCE", Instant.now());
        memoryStore.create(
                architect, UUID.randomUUID(), "answer-style", "architecture tradeoffs first",
                "PREFERENCE", Instant.now());
        assertThat(memoryStore.list(developer)).extracting("value").containsExactly("conclusion first");
        assertThat(memoryStore.list(architect)).extracting("value")
                .containsExactly("architecture tradeoffs first");
        assertThat(memoryStore.list(ACTOR)).isEmpty();

        UUID projectId = projectWatchStore.list(developer).getFirst().id();
        projectWatchStore.setEnabled(developer, projectId, true, Instant.now());
        assertThat(projectWatchStore.list(developer).stream()
                .filter(project -> project.id().equals(projectId)).findFirst().orElseThrow().enabled()).isTrue();
        assertThat(projectWatchStore.list(architect).stream()
                .filter(project -> project.id().equals(projectId)).findFirst().orElseThrow().enabled()).isFalse();
    }

    private static ActorContext actor(String userId) {
        return new ActorContext(UUID.fromString(userId), ACTOR.workspaceId());
    }

    private static void createTestUser(UUID userId, String username, String displayName) {
        jdbcClient.sql("""
                insert into app_user (id, username, display_name, status)
                values (:id, :username, :displayName, 'ACTIVE')
                on conflict (id) do nothing
                """)
                .param("id", userId).param("username", username).param("displayName", displayName).update();
        jdbcClient.sql("""
                insert into workspace_member (workspace_id, user_id, role)
                values (:workspaceId, :userId, 'MEMBER')
                on conflict do nothing
                """)
                .param("workspaceId", ACTOR.workspaceId()).param("userId", userId).update();
    }

    private static ChatStreamController controller(
            com.jundaodsj.insightops.model.application.StreamingChatModelGateway gateway) {
        return new ChatStreamController(
                gateway,
                new ChatStreamSessionRegistry(),
                properties(),
                runStore,
                releaseToolService,
                new P0ChatGuardrail(),
                memoryStore);
    }

    private static DeepSeekModelProperties properties() {
        return new DeepSeekModelProperties(
                true, "https://api.deepseek.com", "deepseek-v4-flash", false,
                0.2, 4096, 4, 90, 2, false);
    }

    private static DeepSeekPricingProperties pricing() {
        return new DeepSeekPricingProperties(
                LocalDate.parse("2026-08-16"),
                new BigDecimal("7.20"),
                new BigDecimal("0.0028"),
                new BigDecimal("0.14"),
                new BigDecimal("0.28"));
    }

    private static ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    private static MockHttpServletRequest request(String traceId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE, traceId);
        request.setAttribute(CurrentAccount.ATTRIBUTE, new AccountWorkspaceStore.AccountRecord(
                ACTOR.userId(), "alpha-owner", "Alpha Owner", ACTOR.workspaceId(),
                "Alpha Workspace", "SYSTEM_ADMIN", "OWNER", "hash", false));
        return request;
    }

    private static ChatStreamSession session(AtomicBoolean cancelled) {
        return new ChatStreamSession() {
            @Override
            public void cancel() {
                cancelled.set(true);
            }

            @Override
            public boolean cancelled() {
                return cancelled.get();
            }
        };
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
