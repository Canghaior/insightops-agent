package com.jundaodsj.insightops.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.agent.application.AgentRunQuery;
import com.jundaodsj.insightops.agent.application.AgentOrchestrationStore;
import com.jundaodsj.insightops.agent.application.AgentCheckpointStore;
import com.jundaodsj.insightops.agent.application.AgentConditionalGraphStore;
import com.jundaodsj.insightops.agent.application.AgentCostGovernanceStore;
import com.jundaodsj.insightops.conversation.application.ChatRunStore;
import com.jundaodsj.insightops.conversation.application.ChatCitation;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import com.jundaodsj.insightops.identity.application.AdminAccountStore;
import com.jundaodsj.insightops.project.application.AdminProjectStore;
import com.jundaodsj.insightops.intelligence.application.IntelligenceStore;
import com.jundaodsj.insightops.project.application.ProjectUpdateStore;
import com.jundaodsj.insightops.infrastructure.config.DeepSeekModelProperties;
import com.jundaodsj.insightops.infrastructure.config.DeepSeekPricingProperties;
import com.jundaodsj.insightops.infrastructure.model.DeepSeekCostEstimator;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcAgentRunQuery;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcAgentOrchestrationStore;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcAgentCheckpointQuery;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcAgentCheckpointStore;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcAgentConditionalGraphStore;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcAgentCostGovernanceStore;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcAdminAccountStore;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcAdminProjectStore;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcAccountWorkspaceStore;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcAgentToolExecutionStore;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcAgentToolApprovalStore;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcChatRunStore;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcKnowledgeUploadStore;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcUserMemoryStore;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcConversationManager;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcProjectUpdateStore;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcIntelligenceStore;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcKnowledgeStore;
import com.jundaodsj.insightops.knowledge.application.KnowledgeCollectionLeaseLostException;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcKnowledgeEmbeddingStore;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcWatchRuleStore;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcResearchFeedbackStore;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcQualityReviewStore;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcReportDeliveryStore;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcUserProjectWatchStore;
import com.jundaodsj.insightops.infrastructure.delivery.DeliverySecretCipher;
import com.jundaodsj.insightops.infrastructure.knowledge.KnowledgeDocumentChunker;
import com.jundaodsj.insightops.knowledge.application.KnowledgeStore;
import com.jundaodsj.insightops.knowledge.application.KnowledgeUploadQuotaExceededException;
import com.jundaodsj.insightops.knowledge.application.KnowledgeUploadStore;
import com.jundaodsj.insightops.knowledge.application.QualityReviewStore;
import com.jundaodsj.insightops.report.application.ReportDeliveryStore;
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
import com.jundaodsj.insightops.server.tool.AgentToolRegistryConfiguration;
import com.jundaodsj.insightops.server.tool.RegisteredToolExecutionService;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import com.jundaodsj.insightops.tool.application.AgentToolApprovalStore;
import com.jundaodsj.insightops.tool.application.github.GitHubRelease;
import com.jundaodsj.insightops.tool.application.registry.AgentToolRegistry;
import com.jundaodsj.insightops.tool.application.registry.AgentToolNames;
import com.jundaodsj.insightops.tool.application.github.GitHubReleaseGateway;
import com.jundaodsj.insightops.tool.application.github.GitHubReleaseQuery;
import com.jundaodsj.insightops.tool.application.github.GitHubReleaseResult;
import com.jundaodsj.insightops.tool.application.github.GitHubProjectEvent;
import com.jundaodsj.insightops.intelligence.application.WatchRuleStore;
import com.jundaodsj.insightops.tool.application.github.GitHubRepositoryReleaseQuery;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
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
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "INSIGHTOPS_CHAIN_GATE", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
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
    private static JdbcAdminProjectStore adminProjectStore;
    private static JdbcProjectUpdateStore projectUpdateStore;
    private static JdbcIntelligenceStore intelligenceStore;
    private static JdbcKnowledgeStore knowledgeStore;
    private static JdbcKnowledgeUploadStore knowledgeUploadStore;
    private static JdbcKnowledgeEmbeddingStore knowledgeEmbeddingStore;
    private static JdbcWatchRuleStore watchRuleStore;
    private static JdbcResearchFeedbackStore feedbackStore;
    private static JdbcQualityReviewStore qualityReviewStore;
    private static JdbcReportDeliveryStore reportDeliveryStore;

    @BeforeAll
    static void prepareIsolatedSchema() throws Exception {
        databaseUrl = environment("DB_URL", "jdbc:postgresql://localhost:55432/insightops");
        username = environment("DB_USERNAME", "insightops");
        password = environment("DB_PASSWORD", "insightops_dev");
        DriverManagerDataSource extensionDataSource = new DriverManagerDataSource(
                databaseUrl, username, password);
        try (Connection connection = extensionDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("create extension if not exists vector with schema public");
        }
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
        assertThat(migration.migrationsExecuted).isEqualTo(34);

        jdbcClient = JdbcClient.create(dataSource);
        assertThat(jdbcClient.sql("select count(*) from tracked_project")
                .query(Long.class).single()).isEqualTo(10L);
        assertThat(jdbcClient.sql("""
                select count(*) from knowledge_source
                where source_type in ('OFFICIAL_BLOG_RSS', 'OFFICIAL_ROADMAP')
                """).query(Long.class).single()).isEqualTo(2L);
        assertThat(jdbcClient.sql("select count(*) from knowledge_upload")
                .query(Long.class).single()).isZero();
        adminAccountStore = new JdbcAdminAccountStore(jdbcClient);
        adminProjectStore = new JdbcAdminProjectStore(jdbcClient);
        projectUpdateStore = new JdbcProjectUpdateStore(jdbcClient, objectMapper());
        intelligenceStore = new JdbcIntelligenceStore(jdbcClient, objectMapper());
        knowledgeStore = new JdbcKnowledgeStore(jdbcClient, objectMapper());
        knowledgeUploadStore = new JdbcKnowledgeUploadStore(jdbcClient);
        knowledgeEmbeddingStore = new JdbcKnowledgeEmbeddingStore(jdbcClient, objectMapper());
        watchRuleStore = new JdbcWatchRuleStore(jdbcClient);
        feedbackStore = new JdbcResearchFeedbackStore(jdbcClient);
        qualityReviewStore = new JdbcQualityReviewStore(jdbcClient, objectMapper());
        reportDeliveryStore = new JdbcReportDeliveryStore(
                jdbcClient, objectMapper(), new DeliverySecretCipher("chain-gate-delivery-secret"));
        DeepSeekCostEstimator estimator = new DeepSeekCostEstimator(pricing());
        runStore = new JdbcChatRunStore(jdbcClient, estimator);
        runQuery = new JdbcAgentRunQuery(jdbcClient, objectMapper());
        memoryStore = new JdbcUserMemoryStore(jdbcClient);
        conversationManager = new JdbcConversationManager(jdbcClient);
        projectWatchStore = new JdbcUserProjectWatchStore(jdbcClient);
        releaseToolService = new ReleaseToolService(
                new ReleaseQuestionRouter(adminProjectStore),
                new GitHubReleaseGateway() {
                    @Override
                    public GitHubReleaseResult listReleases(GitHubReleaseQuery query) {
                        return releaseResult("spring-ai", "Spring AI");
                    }

                    @Override
                    public GitHubReleaseResult listRepositoryReleases(GitHubRepositoryReleaseQuery query) {
                        return releaseResult(query.projectId(), query.displayName());
                    }
                },
                new RegisteredToolExecutionService(
                        new AgentToolRegistry(AgentToolRegistryConfiguration.definitions(true)),
                        new JdbcAgentToolExecutionStore(jdbcClient),
                        objectMapper()),
                new GitHubReleaseEvidenceFormatter());
    }

    private static GitHubReleaseResult releaseResult(String projectId, String projectName) {
        return new GitHubReleaseResult(List.of(new GitHubRelease(
                projectId, projectName, "v2.0.0", projectName + " 2.0.0",
                Instant.parse("2026-06-12T12:00:00Z"),
                "https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0",
                false, "Automated chain gate release evidence.")),
                Instant.parse("2026-08-16T00:00:00Z"));
    }

    @Test
    @Order(1)
    void shouldPersistDeduplicateAndReadProjectUpdates() {
        Instant now = Instant.now().plusSeconds(1);
        List<ProjectUpdateStore.TrackedProject> claimed = projectUpdateStore.claimDueProjects(
                now, Duration.ofMinutes(5), 3);
        ProjectUpdateStore.TrackedProject spring = claimed.stream()
                .filter(project -> project.catalogProjectId().equals("spring-ai"))
                .findFirst().orElseThrow();
        UUID secondUserId = UUID.randomUUID();
        adminAccountStore.createUser(secondUserId, ACTOR.workspaceId(), "update-member", "Update Member",
                "$2a$12$test", "USER", "MEMBER", now);
        ActorContext secondActor = new ActorContext(secondUserId, ACTOR.workspaceId());
        assertThat(projectWatchStore.setEnabled(secondActor, spring.id(), true, now)).isPresent();
        long actorUnreadBefore = projectUpdateStore.unreadCount(ACTOR);
        long secondUnreadBefore = projectUpdateStore.unreadCount(secondActor);
        GitHubRelease release = new GitHubRelease(
                "spring-ai", "Spring AI", "v2.1.0", "Spring AI 2.1.0",
                now.minus(Duration.ofDays(1)),
                "https://github.com/spring-projects/spring-ai/releases/tag/v2.1.0",
                false, "New agent observability and model integrations.");

        ProjectUpdateStore.SyncResult first = projectUpdateStore.completeSuccessfulSync(
                spring, List.of(release), now, now.plus(Duration.ofHours(6)));
        assertThat(first.newEventCount()).isEqualTo(1);
        assertThat(projectUpdateStore.unreadCount(ACTOR)).isEqualTo(actorUnreadBefore + 1);
        assertThat(projectUpdateStore.unreadCount(secondActor)).isEqualTo(secondUnreadBefore + 1);

        ProjectUpdateStore.UpdatePage updates = projectUpdateStore.listUpdates(ACTOR, 0, 20, null, true);
        assertThat(updates.items()).filteredOn(update -> update.versionTag().equals("v2.1.0"))
                .singleElement().satisfies(update -> {
            assertThat(update.versionTag()).isEqualTo("v2.1.0");
            assertThat(update.sourceUrl()).contains("github.com/spring-projects/spring-ai/releases/tag/");
            assertThat(update.read()).isFalse();
        });
        UUID eventId = updates.items().stream().filter(update -> update.versionTag().equals("v2.1.0"))
                .findFirst().orElseThrow().eventId();
        assertThat(projectUpdateStore.markRead(ACTOR, eventId, now.plusSeconds(1))).isTrue();
        assertThat(projectUpdateStore.unreadCount(ACTOR)).isEqualTo(actorUnreadBefore);
        assertThat(projectUpdateStore.unreadCount(secondActor)).isEqualTo(secondUnreadBefore + 1);

        assertThat(projectUpdateStore.requestSync(ACTOR.workspaceId(), spring.id(), now.plusSeconds(2))).isTrue();
        ProjectUpdateStore.TrackedProject secondClaim = projectUpdateStore.claimDueProjects(
                now.plusSeconds(2), Duration.ofMinutes(5), 20).stream()
                .filter(project -> project.id().equals(spring.id())).findFirst().orElseThrow();
        ProjectUpdateStore.SyncResult second = projectUpdateStore.completeSuccessfulSync(
                secondClaim, List.of(release), now.plusSeconds(3), now.plus(Duration.ofHours(6)));
        assertThat(second.newEventCount()).isZero();
        assertThat(projectUpdateStore.listUpdates(ACTOR, 0, 100, null, false).items())
                .filteredOn(update -> update.versionTag().equals("v2.1.0")).hasSize(1);
        assertThat(projectUpdateStore.collectionStatus(ACTOR.workspaceId()))
                .filteredOn(status -> status.projectId().equals(spring.id()))
                .singleElement().extracting(ProjectUpdateStore.CollectionStatus::status)
                .isEqualTo("SUCCEEDED");
    }

    @Test
    @Order(2)
    void shouldQueueAnalyzeNotifyAndDigestNewReleaseWithoutCrossUserLeakage() {
        Instant now = Instant.parse("2026-08-17T09:00:00Z");
        UUID springId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        projectUpdateStore.requestSync(ACTOR.workspaceId(), springId, now);
        ProjectUpdateStore.TrackedProject spring = projectUpdateStore.claimDueProjects(
                now, Duration.ofMinutes(5), 3).stream()
                .filter(project -> project.id().equals(springId)).findFirst().orElseThrow();
        GitHubRelease release = new GitHubRelease(
                "spring-ai", "Spring AI", "v2.2.0", "Spring AI 2.2.0",
                now.minus(Duration.ofHours(1)),
                "https://github.com/spring-projects/spring-ai/releases/tag/v2.2.0",
                false, "New observability API and a breaking chat memory migration.");
        projectUpdateStore.completeSuccessfulSync(spring, List.of(release), now, now.plus(Duration.ofHours(6)));
        UUID eventId = projectUpdateStore.listUpdates(ACTOR, 0, 100, springId, false).items().stream()
                .filter(update -> update.versionTag().equals("v2.2.0")).findFirst().orElseThrow().eventId();

        List<IntelligenceStore.AnalysisTask> tasks = intelligenceStore.claimDueAnalyses(
                now.plusSeconds(1), Duration.ofMinutes(5), 20, 5);
        IntelligenceStore.AnalysisTask task = tasks.stream()
                .filter(candidate -> candidate.eventId().equals(eventId)).findFirst().orElseThrow();
        assertThat(task.automatic()).isTrue();
        var result = new IntelligenceStore.AnalysisResult(
                "HIGH", "TRY", "SUFFICIENT", "可观测性增强，但聊天记忆迁移需要兼容性验证。",
                List.of("新增可观测性 API"), "Java 服务需要验证现有监控集成。", "便于定位 Agent 执行问题。",
                List.of("聊天记忆存在破坏性变更"), List.of("先在测试环境升级"), List.of(release.url()));
        intelligenceStore.completeAnalysis(task, result, new IntelligenceStore.ModelAudit(
                "deepseek", "deepseek-v4-flash", new ModelUsage(500, 200, 700, 0L, 0L),
                new BigDecimal("0.000900"), LocalDate.parse("2026-08-16")), now.plusSeconds(2));

        IntelligenceStore.AnalysisPage page = intelligenceStore.listAnalyses(ACTOR, 0, 20, springId, "HIGH");
        assertThat(page.items()).filteredOn(item -> item.eventId().equals(eventId)).singleElement()
                .satisfies(item -> assertThat(item.oneLineSummary()).contains("兼容性验证"));
        assertThat(intelligenceStore.findAnalysis(ACTOR, task.analysisId())).isPresent();
        assertThat(intelligenceStore.unreadNotifications(ACTOR)).isGreaterThanOrEqualTo(2);

        UUID secondUser = UUID.randomUUID();
        createTestUser(secondUser, "intelligence-isolated", "Intelligence Isolated");
        ActorContext secondActor = new ActorContext(secondUser, ACTOR.workspaceId());
        assertThat(intelligenceStore.findAnalysis(secondActor, task.analysisId())).isEmpty();
        projectWatchStore.setEnabled(secondActor, springId, true, now.plusSeconds(3));
        assertThat(intelligenceStore.findAnalysis(secondActor, task.analysisId())).isPresent();
        assertThat(intelligenceStore.unreadNotifications(secondActor)).isZero();

        IntelligenceStore.DigestPreference preference = intelligenceStore.savePreference(
                ACTOR, "DAILY", "Asia/Shanghai", 9, List.of(springId), now.plusSeconds(4));
        assertThat(preference.cadence()).isEqualTo("DAILY");
        assertThat(intelligenceStore.refreshDueDigests(Instant.parse("2026-08-18T02:00:00Z"))).isEqualTo(1);
        assertThat(intelligenceStore.listDigests(ACTOR, 0, 20).items()).singleElement()
                .satisfies(digest -> assertThat(digest.highRiskCount()).isEqualTo(1));
    }

    @Test
    @Order(3)
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
    @Order(4)
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
            assertThat(payload.get("projectIds")).isEqualTo(List.of(
                    "00000000-0000-0000-0000-000000000101"));
            assertThat(payload.get("maxReleasesPerProject")).isEqualTo(2);
            assertThat(payload.get("includePrereleases")).isEqualTo(false);
        });
        assertThat(detail.toolCalls()).singleElement().satisfies(tool -> {
            assertThat(tool.toolName()).isEqualTo("github_release_list");
            assertThat(tool.status()).isEqualTo("SUCCEEDED");
            assertThat(tool.resultPayload().toString()).contains("v2.0.0");
        });
        JdbcAgentToolExecutionStore attemptStore =
                new JdbcAgentToolExecutionStore(jdbcClient);
        UUID attemptId = UUID.randomUUID();
        UUID toolCallId = detail.toolCalls().getFirst().id();
        Instant attemptStartedAt = Instant.parse("2026-08-16T00:00:00Z");
        attemptStore.startAttempt(attemptId, toolCallId, 1, attemptStartedAt);
        attemptStore.finishAttempt(
                attemptId, "SUCCEEDED", null, false, 0, 300,
                attemptStartedAt.plusMillis(300));
        AgentRunQuery.RunDetail detailWithAttempts =
                runQuery.findRun(ACTOR, summary.id()).orElseThrow();
        assertThat(detailWithAttempts.toolCalls().getFirst().attempts())
                .singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt.attemptNo()).isEqualTo(1);
                    assertThat(attempt.status()).isEqualTo("SUCCEEDED");
                    assertThat(attempt.durationMs()).isEqualTo(300);
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
    @Order(5)
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
    @Order(6)
    void shouldIsolateConversationRunMemoryAndProjectWatchForThreeUsers() {
        ActorContext developer = actor("00000000-0000-0000-0000-000000000102");
        ActorContext architect = actor("00000000-0000-0000-0000-000000000103");
        createTestUser(developer.userId(), "alpha-developer", "Alpha Developer");
        createTestUser(architect.userId(), "alpha-architect", "Alpha Architect");

        UUID developerRun = UUID.randomUUID();
        UUID developerSession = runStore.startRun(
                developer, developerRun, null, "isolation-" + UUID.randomUUID(),
                "developer private question", Instant.now());
        ChatCitation citation = new ChatCitation(
                "S1",
                "Spring AI Reference",
                "https://docs.spring.io/spring-ai/reference/",
                "spring-ai",
                "Overview",
                "OFFICIAL_DOCUMENT",
                0.91d);
        runStore.succeedRunWithCitations(
                developerRun, "developer private answer", "deepseek", "deepseek-v4-flash",
                new ModelUsage(10, 5, 15, 0L, 0L), List.of(citation), Instant.now());

        AgentRunQuery.RunDetail developerRunDetail = runQuery.findRun(developer, developerRun).orElseThrow();
        assertThat(developerRunDetail.citationDetails()).containsExactly(citation);
        assertThat(runQuery.findRun(architect, developerRun)).isEmpty();
        assertThat(runQuery.findRun(ACTOR, developerRun)).isEmpty();
        ChatRunStore.SessionHistory developerHistory = runStore.sessionHistory(
                developer, developerSession, 100).orElseThrow();
        assertThat(developerHistory.messages().getLast().citationDetails()).containsExactly(citation);
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

    @Test
    @Order(7)
    void officialDocumentCollectionPersistsChunksAndDeduplicatesUnchangedRevisions() {
        UUID sourceId = UUID.fromString("00000000-0000-0000-0000-000000000401");
        Instant firstRun = Instant.parse("2026-08-17T10:00:00Z");
        assertThat(knowledgeStore.claimDueSources(firstRun, Duration.ofMinutes(5), 3)).isEmpty();
        assertThat(knowledgeStore.requestSync(ACTOR.workspaceId(), sourceId, firstRun)).isTrue();

        KnowledgeStore.SourceTask firstTask = knowledgeStore.claimDueSources(
                firstRun.plusSeconds(1), Duration.ofMinutes(5), 1).getFirst();
        String content = "# Spring AI\n\n" + "Official Spring AI reference documentation. ".repeat(25);
        var chunks = new KnowledgeDocumentChunker().chunk(content, 200, 20);
        var page = new KnowledgeStore.DocumentPage(
                "https://docs.spring.io/spring-ai/reference/", "Spring AI Reference", "en", null,
                "a".repeat(64), content, null, null, chunks);
        Instant firstHeartbeat = firstRun.plusSeconds(2);
        knowledgeStore.updateCollectionProgress(firstTask,
                new KnowledgeStore.CollectionProgress(200, 14, 3, 1, page.canonicalUrl()),
                firstHeartbeat, Duration.ofMinutes(5));
        var firstResult = knowledgeStore.completeSuccessfulSync(firstTask, List.of(page),
                firstRun.plusSeconds(3), firstRun.plus(Duration.ofHours(24)));

        assertThat(firstResult.newDocuments()).isEqualTo(1);
        assertThat(firstResult.chunkCount()).isEqualTo(chunks.size());
        var firstStatus = knowledgeStore.sourceStatus(ACTOR.workspaceId()).stream()
                .filter(source -> source.sourceId().equals(sourceId)).findFirst().orElseThrow();
        assertThat(firstStatus.documentCount()).isEqualTo(1);
        assertThat(firstStatus.revisionCount()).isEqualTo(1);
        assertThat(firstStatus.chunkCount()).isEqualTo(chunks.size());
        assertThat(firstStatus.lockedUntil()).isNull();
        assertThat(firstStatus.lastJob().maxPageCount()).isEqualTo(200);
        assertThat(firstStatus.lastJob().discoveredUrlCount()).isEqualTo(14);
        assertThat(firstStatus.lastJob().visitedUrlCount()).isEqualTo(3);
        assertThat(firstStatus.lastJob().heartbeatAt()).isEqualTo(firstHeartbeat);
        assertThat(firstStatus.lastJob().currentUrl()).isEqualTo(page.canonicalUrl());

        Instant secondRun = firstRun.plusSeconds(10);
        assertThat(knowledgeStore.requestSync(ACTOR.workspaceId(), sourceId, secondRun)).isTrue();
        KnowledgeStore.SourceTask secondTask = knowledgeStore.claimDueSources(
                secondRun.plusSeconds(1), Duration.ofMinutes(5), 1).getFirst();
        var secondResult = knowledgeStore.completeSuccessfulSync(secondTask, List.of(page),
                secondRun.plusSeconds(2), secondRun.plus(Duration.ofHours(24)));

        assertThat(secondResult.unchangedDocuments()).isEqualTo(1);
        assertThat(secondResult.chunkCount()).isZero();
        var secondStatus = knowledgeStore.sourceStatus(ACTOR.workspaceId()).stream()
                .filter(source -> source.sourceId().equals(sourceId)).findFirst().orElseThrow();
        assertThat(secondStatus.revisionCount()).isEqualTo(1);
        assertThat(secondStatus.chunkCount()).isEqualTo(chunks.size());

        jdbcClient.sql("""
                update knowledge_chunk
                set metadata = metadata - 'chunkPipelineVersion'
                where revision_id = (
                    select current_revision_id from knowledge_document
                    where source_id=:sourceId and canonical_url=:url
                )
                """)
                .param("sourceId", sourceId)
                .param("url", page.canonicalUrl())
                .update();
        Instant thirdRun = firstRun.plusSeconds(20);
        assertThat(knowledgeStore.requestSync(ACTOR.workspaceId(), sourceId, thirdRun)).isTrue();
        KnowledgeStore.SourceTask thirdTask = knowledgeStore.claimDueSources(
                thirdRun.plusSeconds(1), Duration.ofMinutes(5), 1).getFirst();
        var thirdResult = knowledgeStore.completeSuccessfulSync(thirdTask, List.of(page),
                thirdRun.plusSeconds(2), thirdRun.plus(Duration.ofHours(24)));

        assertThat(thirdResult.unchangedDocuments()).isEqualTo(1);
        assertThat(thirdResult.chunkCount()).isEqualTo(chunks.size());
        var thirdStatus = knowledgeStore.sourceStatus(ACTOR.workspaceId()).stream()
                .filter(source -> source.sourceId().equals(sourceId)).findFirst().orElseThrow();
        assertThat(thirdStatus.revisionCount()).isEqualTo(1);
        assertThat(thirdStatus.chunkCount()).isEqualTo(chunks.size());

        Instant embeddingTime = thirdRun.plusSeconds(3);
        assertThat(knowledgeEmbeddingStore.prepareCurrentChunks(
                "ollama", "bge-m3", 1024, embeddingTime)).isEqualTo(chunks.size());
        var embeddingTasks = knowledgeEmbeddingStore.claimPending(
                "bge-m3", embeddingTime, Duration.ofMinutes(5), 20);
        assertThat(embeddingTasks).hasSize(chunks.size());
        float[] vector = new float[1024];
        vector[0] = 1.0f;
        for (var task : embeddingTasks) {
            knowledgeEmbeddingStore.complete(task.chunkId(), "bge-m3", vector, embeddingTime.plusSeconds(1));
        }
        var embeddingOverview = knowledgeEmbeddingStore.overview(ACTOR.workspaceId(), "bge-m3");
        assertThat(embeddingOverview.succeeded()).isEqualTo(chunks.size());
        assertThat(embeddingOverview.failed()).isZero();
        var retrieval = knowledgeEmbeddingStore.search(
                ACTOR.workspaceId(), "bge-m3", vector, 5, 0.5);
        assertThat(retrieval).isNotEmpty();
        assertThat(retrieval.getFirst().canonicalUrl()).isEqualTo(page.canonicalUrl());
        assertThat(retrieval.getFirst().score()).isGreaterThan(0.99);
        knowledgeEmbeddingStore.recordRetrieval(ACTOR.workspaceId(), "Spring AI reference",
                "VECTOR", retrieval.size(), 12, retrieval, embeddingTime.plusSeconds(2));
        assertThat(jdbcClient.sql("select count(*) from retrieval_trace where retrieval_mode='VECTOR'")
                .query(Long.class).single()).isEqualTo(1L);

        Instant staleRun = firstRun.plusSeconds(100);
        assertThat(knowledgeStore.requestSync(ACTOR.workspaceId(), sourceId, staleRun)).isTrue();
        KnowledgeStore.SourceTask staleTask = knowledgeStore.claimDueSources(
                staleRun.plusSeconds(1), Duration.ofMinutes(1), 1).getFirst();
        KnowledgeStore.SourceTask replacementTask = knowledgeStore.claimDueSources(
                staleRun.plusSeconds(62), Duration.ofMinutes(5), 1).getFirst();

        assertThat(replacementTask.jobId()).isNotEqualTo(staleTask.jobId());
        assertThatThrownBy(() -> knowledgeStore.updateCollectionProgress(staleTask,
                new KnowledgeStore.CollectionProgress(200, 1, 1, 0, page.canonicalUrl()),
                staleRun.plusSeconds(63), Duration.ofMinutes(1)))
                .isInstanceOf(KnowledgeCollectionLeaseLostException.class);
        assertThatThrownBy(() -> knowledgeStore.completeSuccessfulSync(
                staleTask, List.of(page), staleRun.plusSeconds(63), staleRun.plus(Duration.ofHours(24))))
                .isInstanceOf(KnowledgeCollectionLeaseLostException.class);

        knowledgeStore.updateCollectionProgress(replacementTask,
                new KnowledgeStore.CollectionProgress(200, 1, 1, 1, page.canonicalUrl()),
                staleRun.plusSeconds(63), Duration.ofMinutes(5));
        knowledgeStore.completeSuccessfulSync(replacementTask, List.of(page),
                staleRun.plusSeconds(64), staleRun.plus(Duration.ofHours(24)));
        assertThat(jdbcClient.sql("""
                select error_code from knowledge_collection_job where id=:jobId
                """).param("jobId", staleTask.jobId()).query(String.class).single())
                .isEqualTo("LOCK_EXPIRED");

        UUID customSourceId = UUID.randomUUID();
        var customDefinition = new KnowledgeStore.SourceDefinition(
                customSourceId, ACTOR.workspaceId(), firstTask.projectId(),
                "gate-custom-" + customSourceId.toString().substring(0, 8), "Gate Docs",
                "OFFICIAL_DOCUMENTATION", "https://docs.example.com/guide/",
                "https://docs.example.com/sitemap.xml", "docs.example.com", "/guide/",
                "T1_PROJECT_DOMAIN", 7);
        var custom = knowledgeStore.createSource(customDefinition, staleRun.plusSeconds(70));
        assertThat(custom.syncIntervalHours()).isEqualTo(7);
        var changedDefinition = new KnowledgeStore.SourceDefinition(
                customSourceId, ACTOR.workspaceId(), firstTask.projectId(), custom.sourceKey(),
                "Updated Gate Docs", custom.sourceType(), custom.rootUrl(), custom.discoveryUrl(),
                custom.allowedHost(), custom.allowedPathPrefix(), custom.trustTier(), 9);
        assertThat(knowledgeStore.updateSource(ACTOR.workspaceId(), customSourceId,
                changedDefinition, staleRun.plusSeconds(71))).get()
                .extracting(KnowledgeStore.SourceStatus::syncIntervalHours).isEqualTo(9);
        assertThat(knowledgeStore.setSourceEnabled(ACTOR.workspaceId(), customSourceId,
                false, staleRun.plusSeconds(72))).get()
                .extracting(KnowledgeStore.SourceStatus::enabled).isEqualTo(false);
        assertThat(knowledgeStore.deleteEmptySource(ACTOR.workspaceId(), customSourceId))
                .isEqualTo(KnowledgeStore.DeleteResult.DELETED);
    }

    @Test
    @Order(8)
    void shouldManageDynamicProjectsWithDatabaseDependencyGuards() {
        UUID projectId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-19T08:00:00Z");
        var created = adminProjectStore.create(
                projectId, ACTOR.workspaceId(), "chain-gate", "insightops-fixture",
                "https://github.com/chain-gate/insightops-fixture", 2, 12,
                List.of("chain gate fixture"), now);

        assertThat(created.repositoryName()).isEqualTo("insightops-fixture");
        assertThat(created.enabled()).isTrue();
        assertThat(created.nextSyncAt()).isEqualTo(now);
        assertThat(created.syncIntervalHours()).isEqualTo(12);
        assertThat(created.chatAliases()).containsExactly("chain gate fixture");
        assertThat(adminProjectStore.list(ACTOR.workspaceId()))
                .extracting(AdminProjectStore.ManagedProject::projectId)
                .contains(projectId);

        ProjectUpdateStore.TrackedProject claimed = projectUpdateStore.claimDueProjects(
                        now.plusSeconds(1), Duration.ofMinutes(5), 20).stream()
                .filter(project -> project.id().equals(projectId))
                .findFirst().orElseThrow();
        assertThat(claimed.owner()).isEqualTo("chain-gate");
        assertThat(claimed.repository()).isEqualTo("insightops-fixture");
        assertThat(claimed.syncIntervalHours()).isEqualTo(12);
        projectUpdateStore.completeSuccessfulSync(
                claimed, List.of(), now.plusSeconds(1), now.plus(Duration.ofHours(6)));

        var updated = adminProjectStore.update(
                ACTOR.workspaceId(), projectId, "chain-gate", "insightops-fixture-v2",
                "https://github.com/chain-gate/insightops-fixture-v2", 4, 8,
                List.of("chain gate fixture v2"), now.plusSeconds(1))
                .orElseThrow();
        assertThat(updated.repositoryName()).isEqualTo("insightops-fixture-v2");
        assertThat(updated.priority()).isEqualTo(4);
        assertThat(updated.syncIntervalHours()).isEqualTo(8);
        assertThat(updated.chatAliases()).containsExactly("chain gate fixture v2");

        assertThat(adminProjectStore.setEnabled(
                ACTOR.workspaceId(), projectId, false, now.plusSeconds(2)))
                .get().extracting(AdminProjectStore.ManagedProject::enabled).isEqualTo(false);

        jdbcClient.sql("""
                insert into user_project_watch (user_id, workspace_id, project_id, enabled)
                values (:userId, :workspaceId, :projectId, true)
                """)
                .param("userId", ACTOR.userId())
                .param("workspaceId", ACTOR.workspaceId())
                .param("projectId", projectId)
                .update();
        assertThat(adminProjectStore.deleteEmpty(ACTOR.workspaceId(), projectId))
                .isEqualTo(AdminProjectStore.DeleteResult.HAS_DEPENDENCIES);

        jdbcClient.sql("delete from user_project_watch where project_id=:projectId")
                .param("projectId", projectId).update();
        assertThat(adminProjectStore.deleteEmpty(ACTOR.workspaceId(), projectId))
                .isEqualTo(AdminProjectStore.DeleteResult.DELETED);
        assertThat(adminProjectStore.find(ACTOR.workspaceId(), projectId)).isEmpty();
    }

    @Test
    @Order(9)
    void proactiveIntelligenceRulesEventsNotificationsAndFeedbackFormAClosedLoop() {
        UUID projectId = jdbcClient.sql("""
                select id from tracked_project
                where workspace_id=:workspaceId and repository_name='spring-ai'
                """).param("workspaceId", ACTOR.workspaceId()).query(UUID.class).single();
        WatchRuleStore.WatchRule rule = watchRuleStore.create(ACTOR, new WatchRuleStore.RuleCommand(
                "Spring AI security", projectId, List.of("security"), List.of("documentation"),
                List.of("GITHUB_ISSUE", "GITHUB_SECURITY_ADVISORY"), 3,
                true, true, true), Instant.now());
        assertThat(rule.enabled()).isTrue();

        var project = new ProjectUpdateStore.TrackedProject(
                projectId, ACTOR.workspaceId(), "spring-ai", "spring-projects", "spring-ai", 6, 0);
        GitHubProjectEvent issue = new GitHubProjectEvent(
                "issue:999999", "GITHUB_ISSUE", "Security regression in vector store",
                "A security regression affects vector store initialization.",
                "https://github.com/spring-projects/spring-ai/issues/999999", "OPEN", "maintainer",
                List.of("security", "bug"), null, 4, Instant.now(), Instant.now(),
                "{\"number\":999999,\"state\":\"open\"}");
        assertThat(projectUpdateStore.storeProjectEvents(project, List.of(issue), Instant.now())).isEqualTo(1);
        assertThat(projectUpdateStore.storeProjectEvents(project, List.of(issue), Instant.now())).isZero();

        ProjectUpdateStore.UpdatePage page = projectUpdateStore.listUpdates(
                ACTOR, 0, 20, projectId, false, "GITHUB_ISSUE", null, true);
        assertThat(page.items()).anySatisfy(item -> {
            assertThat(item.eventType()).isEqualTo("GITHUB_ISSUE");
            assertThat(item.matchedRuleCount()).isEqualTo(1);
            assertThat(item.labels()).contains("security", "bug");
        });
        assertThat(intelligenceStore.listNotifications(ACTOR, 0, 20, false).items())
                .anySatisfy(notification -> assertThat(notification.type()).isEqualTo("RULE_MATCH"));
        assertThat(projectUpdateStore.searchEvents(ACTOR.workspaceId(), "", 10, List.of("GITHUB_ISSUE")))
                .anySatisfy(event -> assertThat(event.sourceUrl()).endsWith("/issues/999999"));

        UUID runId = jdbcClient.sql("""
                select id from agent_run where owner_user_id=:userId and workspace_id=:workspaceId
                order by created_at desc limit 1
                """).param("userId", ACTOR.userId()).param("workspaceId", ACTOR.workspaceId())
                .query(UUID.class).single();
        assertThat(feedbackStore.saveAnswerFeedback(
                ACTOR, runId, false, "MISSING_EVIDENCE", "Missing the issue evidence", Instant.now())).isTrue();
        assertThat(feedbackStore.saveCitationFeedback(
                ACTOR, runId, issue.sourceUrl(), true, null, Instant.now())).isTrue();
        assertThat(jdbcClient.sql("select count(*) from research_answer_feedback where run_id=:runId")
                .param("runId", runId).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @Order(10)
    void feedbackReviewCandidateVersionAndActivationRequireAPassedGate() {
        var feedbackPage = qualityReviewStore.listFeedback(ACTOR.workspaceId(), 0, 20, "PENDING", null);
        assertThat(feedbackPage.total()).isGreaterThanOrEqualTo(2);
        var answerFeedback = feedbackPage.items().stream()
                .filter(item -> "ANSWER".equals(item.type())).findFirst().orElseThrow();

        var candidateCommand = new QualityReviewStore.CandidateCommand(
                "Which Spring AI evidence is required for vector store initialization?",
                true, "spring-ai", "feedback-regression", List.of("vector store"),
                List.of("initialization"), "docs.spring.io");
        var reviewed = qualityReviewStore.reviewFeedback(
                ACTOR.workspaceId(), ACTOR.userId(), answerFeedback.id(), "ANSWER",
                new QualityReviewStore.ReviewCommand(
                        "ADD_TO_EVAL", "Verified missing evidence", candidateCommand), Instant.now())
                .orElseThrow();
        assertThat(reviewed.reviewStatus()).isEqualTo("ADDED_TO_EVAL");
        assertThat(reviewed.candidateId()).isNotNull();

        var candidate = qualityReviewStore.listCandidates(
                ACTOR.workspaceId(), 0, 20, "DRAFT").items().getFirst();
        assertThat(candidate.question()).contains("Spring AI");
        candidate = qualityReviewStore.decideCandidate(
                ACTOR.workspaceId(), ACTOR.userId(), candidate.id(), "APPROVED",
                "Ready for the next quality gate", Instant.now()).orElseThrow();
        assertThat(candidate.status()).isEqualTo("APPROVED");

        var version = qualityReviewStore.createVersion(
                ACTOR.workspaceId(), ACTOR.userId(), "p1-rag-feedback-v1",
                List.of(candidate.id()), Instant.now());
        assertThat(version.status()).isEqualTo("DRAFT");
        assertThat(version.candidateCount()).isEqualTo(1);
        assertThatThrownBy(() -> qualityReviewStore.createVersion(
                ACTOR.workspaceId(), ACTOR.userId(), "p1-rag-questions-v3-50",
                List.of(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved");
        assertThatThrownBy(() -> qualityReviewStore.activateVersion(
                ACTOR.workspaceId(), ACTOR.userId(), version.id(), Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pass the RAG evaluation gate");

        var gateStartedAt = Instant.now().atOffset(java.time.ZoneOffset.UTC);
        UUID olderPassedRunId = UUID.randomUUID();
        jdbcClient.sql("""
                insert into rag_evaluation_run
                    (id,workspace_id,dataset_name,status,case_count,generation_sample_size,
                     recall_at_k,mean_reciprocal_rank,project_hit_rate,term_coverage,
                     no_answer_accuracy,started_at,finished_at)
                values (:id,:workspaceId,:name,'PASSED',51,0,1,1,1,1,1,:now,:now)
                """).param("id", olderPassedRunId).param("workspaceId", ACTOR.workspaceId())
                .param("name", version.name()).param("now", gateStartedAt)
                .update();
        UUID gateRunId = UUID.randomUUID();
        jdbcClient.sql("""
                insert into rag_evaluation_run
                    (id,workspace_id,dataset_name,status,case_count,generation_sample_size,
                     started_at,finished_at,error_message)
                values (:id,:workspaceId,:name,'FAILED',51,0,:now,:now,'quality regression')
                """).param("id", gateRunId).param("workspaceId", ACTOR.workspaceId())
                .param("name", version.name()).param("now", gateStartedAt.plusSeconds(1))
                .update();
        assertThatThrownBy(() -> qualityReviewStore.activateVersion(
                ACTOR.workspaceId(), ACTOR.userId(), version.id(), Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("latest RAG evaluation run");
        jdbcClient.sql("""
                update rag_evaluation_run set status='PASSED', error_message=null,
                    recall_at_k=1, mean_reciprocal_rank=1, project_hit_rate=1,
                    term_coverage=1, no_answer_accuracy=1 where id=:id
                """).param("id", gateRunId).update();
        var active = qualityReviewStore.activateVersion(
                ACTOR.workspaceId(), ACTOR.userId(), version.id(), Instant.now()).orElseThrow();
        assertThat(active.status()).isEqualTo("ACTIVE");
        assertThat(active.gateRunId()).isEqualTo(gateRunId);
        assertThat(qualityReviewStore.datasetSelection(ACTOR.workspaceId(), null)).get()
                .satisfies(selection -> {
                    assertThat(selection.name()).isEqualTo("p1-rag-feedback-v1");
                    assertThat(selection.cases()).hasSize(1);
                    assertThat(selection.cases().getFirst().mustHitTerms()).contains("vector store");
                });
    }

    @Test
    @Order(11)
    void reportsExportsAndWebhookDeliveryRemainScopedIdempotentAndAuditable() {
        Instant now = Instant.parse("2026-08-20T02:00:00Z");
        var query = new ReportDeliveryStore.ReportQuery(
                "P1.8 production report", Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-09-01T00:00:00Z"), List.of(),
                List.of("GITHUB_RELEASE"), 50);
        var selected = reportDeliveryStore.selectReportItems(ACTOR, query);
        assertThat(selected).isNotEmpty();

        UUID reportId = UUID.randomUUID();
        var report = reportDeliveryStore.createReport(
                ACTOR, reportId, query, selected, "# P1.8 production report\n\nSnapshot body.", now);
        assertThat(report.itemCount()).isEqualTo(selected.size());
        assertThat(report.highRiskCount()).isGreaterThanOrEqualTo(1);
        assertThat(reportDeliveryStore.listReports(ACTOR, 0, 20).items())
                .extracting(ReportDeliveryStore.ReportRecord::id).contains(reportId);

        UUID otherUser = UUID.randomUUID();
        createTestUser(otherUser, "report-isolated", "Report Isolated");
        ActorContext otherActor = new ActorContext(otherUser, ACTOR.workspaceId());
        assertThat(reportDeliveryStore.findReport(otherActor, reportId)).isEmpty();

        String endpoint = "https://hooks.example.com/secret-delivery-token";
        UUID channelId = UUID.randomUUID();
        var channel = reportDeliveryStore.createChannel(
                ACTOR, channelId, "Release webhook", endpoint, true, now.plusSeconds(1));
        assertThat(channel.endpointMasked()).isEqualTo("https://hooks.example.com/***");
        assertThat(channel.endpointMasked()).doesNotContain("secret-delivery-token");
        assertThatThrownBy(() -> reportDeliveryStore.createChannel(
                ACTOR, UUID.randomUUID(), "release WEBHOOK", endpoint, true, now.plusSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");

        var delivery = reportDeliveryStore.enqueueDelivery(
                ACTOR, reportId, channelId, now.plusSeconds(3)).orElseThrow();
        var duplicate = reportDeliveryStore.enqueueDelivery(
                ACTOR, reportId, channelId, now.plusSeconds(4)).orElseThrow();
        assertThat(duplicate.id()).isEqualTo(delivery.id());
        assertThat(reportDeliveryStore.listDeliveries(otherActor, 0, 20, null).items()).isEmpty();
        assertThat(reportDeliveryStore.retryDelivery(otherActor, delivery.id(), now.plusSeconds(5))).isEmpty();

        var firstTask = reportDeliveryStore.claimDueDeliveries(
                now.plusSeconds(5), Duration.ofMinutes(5), 10).getFirst();
        assertThat(firstTask.endpointUrl()).isEqualTo(endpoint);
        assertThat(firstTask.attempts()).isEqualTo(1);
        Instant retryAt = now.plus(Duration.ofMinutes(5));
        reportDeliveryStore.failDelivery(
                firstTask.deliveryId(), firstTask.leaseToken(), "HTTP_503", "upstream unavailable",
                503, 42, now.plusSeconds(6), retryAt, false);
        assertThat(reportDeliveryStore.listDeliveries(ACTOR, 0, 20, reportId).items().getFirst())
                .satisfies(item -> {
                    assertThat(item.status()).isEqualTo("RETRY_WAIT");
                    assertThat(item.responseCode()).isEqualTo(503);
                    assertThat(item.lastError()).contains("upstream unavailable");
                });

        assertThat(reportDeliveryStore.claimDueDeliveries(
                retryAt.minusSeconds(1), Duration.ofMinutes(5), 10)).isEmpty();
        var retryTask = reportDeliveryStore.claimDueDeliveries(
                retryAt, Duration.ofMinutes(5), 10).getFirst();
        assertThat(retryTask.attempts()).isEqualTo(2);
        reportDeliveryStore.completeDelivery(
                retryTask.deliveryId(), retryTask.leaseToken(), 204, 31, retryAt.plusSeconds(1));
        assertThat(reportDeliveryStore.listDeliveries(ACTOR, 0, 20, reportId).items().getFirst())
                .satisfies(item -> {
                    assertThat(item.status()).isEqualTo("SUCCEEDED");
                    assertThat(item.responseCode()).isEqualTo(204);
                    assertThat(item.durationMs()).isEqualTo(31L);
                });

        assertThat(reportDeliveryStore.deleteChannel(ACTOR, channelId, retryAt.plusSeconds(2))).isTrue();
        assertThat(reportDeliveryStore.listChannels(ACTOR)).isEmpty();
        assertThat(reportDeliveryStore.listDeliveries(ACTOR, 0, 20, reportId).items())
                .singleElement().extracting(ReportDeliveryStore.DeliveryRecord::status).isEqualTo("SUCCEEDED");
    }


    @Test
    @Order(12)
    void uploadedKnowledgeVisibilityRemainsScopedAndDeletable() {
        Instant now = Instant.parse("2026-08-20T03:00:00Z");
        UUID otherUser = UUID.randomUUID();
        createTestUser(otherUser, "upload-isolated", "Upload Isolated");
        UUID projectId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID privateUpload = UUID.randomUUID();
        UUID workspaceUpload = UUID.randomUUID();
        knowledgeUploadStore.create(new KnowledgeUploadStore.CreateUpload(
                privateUpload, UUID.randomUUID(), ACTOR.workspaceId(), projectId, ACTOR.userId(),
                "private.md", privateUpload + ".bin", "text/markdown", 100,
                "a".repeat(64), "PRIVATE", 1_073_741_824L), now);
        knowledgeUploadStore.create(new KnowledgeUploadStore.CreateUpload(
                workspaceUpload, UUID.randomUUID(), ACTOR.workspaceId(), projectId, ACTOR.userId(),
                "workspace.txt", workspaceUpload + ".bin", "text/plain", 200,
                "b".repeat(64), "WORKSPACE", 1_073_741_824L), now.plusSeconds(1));

        assertThat(knowledgeUploadStore.listVisible(ACTOR.workspaceId(), ACTOR.userId(), false))
                .extracting(KnowledgeUploadStore.UploadRecord::uploadId)
                .contains(privateUpload, workspaceUpload);
        assertThat(knowledgeUploadStore.listVisible(ACTOR.workspaceId(), otherUser, false))
                .extracting(KnowledgeUploadStore.UploadRecord::uploadId)
                .containsExactly(workspaceUpload);
        assertThat(knowledgeUploadStore.listVisible(ACTOR.workspaceId(), otherUser, true))
                .extracting(KnowledgeUploadStore.UploadRecord::uploadId)
                .contains(privateUpload, workspaceUpload);
        assertThat(knowledgeUploadStore.workspaceBytes(ACTOR.workspaceId())).isEqualTo(300L);
        UUID rejectedUpload = UUID.randomUUID();
        assertThatThrownBy(() -> knowledgeUploadStore.create(new KnowledgeUploadStore.CreateUpload(
                rejectedUpload, UUID.randomUUID(), ACTOR.workspaceId(), projectId, ACTOR.userId(),
                "over-quota.txt", rejectedUpload + ".bin", "text/plain", 1,
                "d".repeat(64), "PRIVATE", 300L), now.plusSeconds(2)))
                .isInstanceOf(KnowledgeUploadQuotaExceededException.class);
        assertThat(knowledgeUploadStore.workspaceBytes(ACTOR.workspaceId())).isEqualTo(300L);

        assertThat(knowledgeUploadStore.delete(
                ACTOR.workspaceId(), otherUser, false, privateUpload)).isEmpty();
        assertThat(knowledgeUploadStore.delete(
                ACTOR.workspaceId(), ACTOR.userId(), false, privateUpload)).isPresent();
        assertThat(knowledgeUploadStore.findVisible(
                ACTOR.workspaceId(), ACTOR.userId(), false, privateUpload)).isEmpty();
    }

    @Test
    @Order(13)
    void externalKnowledgeEventsPreserveOfficialPublicationTime() {
        UUID sourceId = UUID.fromString("00000000-0000-0000-0000-000000000404");
        Instant collectedAt = Instant.parse("2026-08-20T04:00:00Z");
        Instant publishedAt = Instant.parse("2026-08-19T10:30:00Z");
        jdbcClient.sql("update knowledge_source set next_sync_at=:later where id<>:sourceId")
                .param("later", java.time.OffsetDateTime.parse("2099-01-01T00:00:00Z"))
                .param("sourceId", sourceId).update();
        assertThat(knowledgeStore.requestSync(ACTOR.workspaceId(), sourceId, collectedAt)).isTrue();
        KnowledgeStore.SourceTask task = knowledgeStore.claimDueSources(
                collectedAt.plusSeconds(1), Duration.ofMinutes(5), 1).getFirst();
        assertThat(task.sourceId()).isEqualTo(sourceId);

        String content = "# Spring Blog Update\n\nA production-ready Spring AI update.";
        var chunks = new KnowledgeDocumentChunker().chunk(content, 200, 20);
        var page = new KnowledgeStore.DocumentPage(
                "https://spring.io/blog/2026/08/19/spring-ai-update",
                "Spring AI update", "en", "Wed, 19 Aug 2026 10:30:00 GMT",
                "c".repeat(64), content, null, null, chunks);
        knowledgeStore.completeSuccessfulSync(task, List.of(page),
                collectedAt.plusSeconds(2), collectedAt.plus(Duration.ofHours(6)));

        assertThat(jdbcClient.sql("""
                select published_at from source_snapshot where source_url=:url
                """).param("url", page.canonicalUrl())
                .query(java.time.OffsetDateTime.class).single().toInstant()).isEqualTo(publishedAt);
        assertThat(jdbcClient.sql("""
                select event.occurred_at from intelligence_event event
                join source_snapshot snapshot on snapshot.id=event.snapshot_id
                where snapshot.source_url=:url
                """).param("url", page.canonicalUrl())
                .query(java.time.OffsetDateTime.class).single().toInstant()).isEqualTo(publishedAt);
        assertThat(jdbcClient.sql("""
                select event_type from intelligence_event event
                join source_snapshot snapshot on snapshot.id=event.snapshot_id
                where snapshot.source_url=:url
                """).param("url", page.canonicalUrl())
                .query(String.class).single()).isEqualTo("OFFICIAL_BLOG");
    }

    @Test
    @Order(14)
    void mutatingToolApprovalIsIdempotentRejectableAndCompensatable() {
        JdbcAgentToolExecutionStore executions = new JdbcAgentToolExecutionStore(jdbcClient);
        JdbcAgentToolApprovalStore approvals = new JdbcAgentToolApprovalStore(
                jdbcClient, objectMapper());
        Instant now = Instant.parse("2026-08-22T02:00:00Z");
        UUID runId = UUID.randomUUID();
        runStore.startRun(ACTOR, runId, null, "p2d-approval-" + runId,
                "记住：先给结论", now);

        UUID stepId = UUID.randomUUID();
        UUID toolCallId = UUID.randomUUID();
        String payload = "{\"key\":\"p2d-approval-memory\","
                + "\"value\":\"conclusion first\",\"category\":\"PREFERENCE\"}";
        executions.startTool(runId, stepId, toolCallId, 1,
                AgentToolNames.USER_MEMORY_UPSERT, "p2d:approval:" + runId,
                payload, now);
        UUID approvalId = UUID.randomUUID();
        AgentToolApprovalStore.Approval pending = approvals.request(
                new AgentToolApprovalStore.Request(
                        approvalId, runId, stepId, toolCallId, ACTOR.userId(),
                        ACTOR.workspaceId(), AgentToolNames.USER_MEMORY_UPSERT,
                        "写入长期记忆", payload, "p2d:approval:" + runId,
                        now.plusSeconds(1_800), now));
        executions.waitForApproval(stepId, toolCallId,
                "{\"status\":\"WAITING_APPROVAL\"}", 1, now);
        assertThat(pending.status()).isEqualTo("PENDING");

        AgentToolApprovalStore.Approval executed = approvals.approve(
                ACTOR, approvalId, "数据库验收", now.plusSeconds(1));
        assertThat(executed.status()).isEqualTo("EXECUTED");
        assertThat(memoryStore.list(ACTOR).stream()
                .filter(memory -> memory.key().equals("p2d-approval-memory")))
                .singleElement().extracting("value").isEqualTo("conclusion first");

        AgentToolApprovalStore.Approval repeated = approvals.approve(
                ACTOR, approvalId, "重复确认", now.plusSeconds(2));
        assertThat(repeated.status()).isEqualTo("EXECUTED");
        assertThat(memoryStore.list(ACTOR).stream()
                .filter(memory -> memory.key().equals("p2d-approval-memory"))).hasSize(1);

        jdbcClient.sql("""
                update user_memory set memory_value = 'newer user edit'
                where user_id = :userId and workspace_id = :workspaceId
                  and memory_key = 'p2d-approval-memory'
                """)
                .param("userId", ACTOR.userId())
                .param("workspaceId", ACTOR.workspaceId()).update();
        assertThatThrownBy(() -> approvals.compensate(
                ACTOR, approvalId, "不得覆盖后续编辑", now.plusSeconds(3)))
                .isInstanceOf(AgentToolApprovalStore.ApprovalException.class)
                .extracting(error -> ((AgentToolApprovalStore.ApprovalException) error).code())
                .isEqualTo("APPROVAL_EFFECT_CONFLICT");
        jdbcClient.sql("""
                update user_memory set memory_value = 'conclusion first'
                where user_id = :userId and workspace_id = :workspaceId
                  and memory_key = 'p2d-approval-memory'
                """)
                .param("userId", ACTOR.userId())
                .param("workspaceId", ACTOR.workspaceId()).update();

        assertThat(approvals.compensate(
                ACTOR, approvalId, "恢复执行前状态", now.plusSeconds(3)).status())
                .isEqualTo("COMPENSATED");
        assertThat(approvals.compensate(
                ACTOR, approvalId, "重复补偿", now.plusSeconds(4)).status())
                .isEqualTo("COMPENSATED");
        assertThat(memoryStore.list(ACTOR)).noneMatch(
                memory -> memory.key().equals("p2d-approval-memory"));

        UUID rejectedRun = UUID.randomUUID();
        runStore.startRun(ACTOR, rejectedRun, null, "p2d-reject-" + rejectedRun,
                "记住一条不应执行的内容", now.plusSeconds(5));
        UUID rejectedStep = UUID.randomUUID();
        UUID rejectedCall = UUID.randomUUID();
        String rejectedPayload = "{\"key\":\"p2d-rejected-memory\","
                + "\"value\":\"must not exist\",\"category\":\"PREFERENCE\"}";
        executions.startTool(rejectedRun, rejectedStep, rejectedCall, 1,
                AgentToolNames.USER_MEMORY_UPSERT, "p2d:reject:" + rejectedRun,
                rejectedPayload, now.plusSeconds(5));
        UUID rejectedApproval = UUID.randomUUID();
        approvals.request(new AgentToolApprovalStore.Request(
                rejectedApproval, rejectedRun, rejectedStep, rejectedCall,
                ACTOR.userId(), ACTOR.workspaceId(), AgentToolNames.USER_MEMORY_UPSERT,
                "拒绝长期记忆", rejectedPayload, "p2d:reject:" + rejectedRun,
                now.plusSeconds(1_805), now.plusSeconds(5)));
        executions.waitForApproval(rejectedStep, rejectedCall,
                "{\"status\":\"WAITING_APPROVAL\"}", 1, now.plusSeconds(5));
        assertThat(approvals.reject(
                ACTOR, rejectedApproval, "拒绝执行", now.plusSeconds(6)).status())
                .isEqualTo("REJECTED");
        assertThat(memoryStore.list(ACTOR)).noneMatch(
                memory -> memory.key().equals("p2d-rejected-memory"));
    }

    @Test
    @Order(15)
    void orchestrationGraphAndBudgetAreDurableAndQueryable() {
        JdbcAgentOrchestrationStore orchestration =
                new JdbcAgentOrchestrationStore(jdbcClient);
        Instant now = Instant.parse("2026-08-22T03:00:00Z");
        UUID runId = UUID.randomUUID();
        runStore.startRun(ACTOR, runId, null, "p21a-graph-" + runId,
                "并行检索 Release 和官方文档", now);
        AgentOrchestrationStore.RunLimits limits =
                new AgentOrchestrationStore.RunLimits(
                        12, 3, 8, 16_000, new BigDecimal("0.500000"));
        AgentOrchestrationStore.PlanHandle plan =
                orchestration.startRun(runId, limits, now);

        AgentOrchestrationStore.NodeDraft release =
                new AgentOrchestrationStore.NodeDraft(
                        UUID.randomUUID(), "call-release", 1, "github_release_list",
                        "READ_ONLY", true, "{\"limit\":10}");
        AgentOrchestrationStore.NodeDraft knowledge =
                new AgentOrchestrationStore.NodeDraft(
                        UUID.randomUUID(), "call-knowledge", 2, "knowledge_hybrid_search",
                        "READ_ONLY", true, "{\"query\":\"ChatClient\"}");
        orchestration.appendLayer(
                plan.planId(), runId, 1, List.of(release, knowledge), List.of(), now);
        AgentOrchestrationStore.NodeDraft synthesis =
                new AgentOrchestrationStore.NodeDraft(
                        UUID.randomUUID(), "call-events", 1,
                        "project_intelligence_event_search", "READ_ONLY", true,
                        "{\"eventTypes\":[\"GITHUB_ISSUE\"]}");
        orchestration.appendLayer(
                plan.planId(), runId, 2, List.of(synthesis),
                List.of(release.id(), knowledge.id()), now.plusSeconds(1));
        orchestration.updateNode(
                release.id(), "SUCCEEDED", null, null, now.plusSeconds(2));
        orchestration.updateNode(
                knowledge.id(), "SUCCEEDED", null, null, now.plusSeconds(2));
        orchestration.updateNode(
                synthesis.id(), "SKIPPED", null, "MAX_MODEL_TOKENS", now.plusSeconds(3));
        orchestration.updateBudget(runId,
                new AgentOrchestrationStore.BudgetSnapshot(
                        3, 2, 4_200, new BigDecimal("0.012300"),
                        "EXHAUSTED", "MAX_MODEL_TOKENS"), now.plusSeconds(3));
        orchestration.finishPlan(plan.planId(), "LIMIT_REACHED", now.plusSeconds(3));

        AgentRunQuery.RunDetail detail = runQuery.findRun(ACTOR, runId).orElseThrow();
        assertThat(detail.plan()).isNotNull();
        assertThat(detail.plan().nodes()).hasSize(3);
        assertThat(detail.plan().nodes().get(2).dependencyIds())
                .containsExactly(release.id(), knowledge.id());
        assertThat(detail.budget()).isNotNull();
        assertThat(detail.budget().status()).isEqualTo("EXHAUSTED");
        assertThat(detail.budget().exhaustionReason()).isEqualTo("MAX_MODEL_TOKENS");
        assertThat(detail.budget().estimatedCostCny()).isEqualByComparingTo("0.012300");
    }

    @Test
    @Order(16)
    void conditionalGraphPauseCheckpointAndResumeAreDurable() {
        JdbcAgentOrchestrationStore orchestration = new JdbcAgentOrchestrationStore(jdbcClient);
        JdbcAgentConditionalGraphStore conditional = new JdbcAgentConditionalGraphStore(jdbcClient);
        JdbcAgentCheckpointStore checkpoints = new JdbcAgentCheckpointStore(jdbcClient);
        JdbcAgentCheckpointQuery checkpointQuery = new JdbcAgentCheckpointQuery(jdbcClient);
        Instant now = Instant.parse("2026-08-22T04:00:00Z");
        UUID runId = UUID.randomUUID();
        runStore.startRun(ACTOR, runId, null, "p21b-conditional-" + runId,
                "Release 失败时改查知识库", now);
        AgentOrchestrationStore.PlanHandle plan = orchestration.startRun(runId,
                new AgentOrchestrationStore.RunLimits(
                        12, 3, 8, 16_000, new BigDecimal("0.500000")), now);
        UUID releaseId = UUID.randomUUID();
        UUID fallbackId = UUID.randomUUID();
        conditional.appendGraph(plan.planId(), runId, 1, 2, List.of(
                new AgentConditionalGraphStore.GraphNodeDraft(
                        releaseId, "release", 1, "github_release_list", "READ_ONLY", true,
                        "{\"limit\":10}", List.of(), "ALWAYS", "[]"),
                new AgentConditionalGraphStore.GraphNodeDraft(
                        fallbackId, "fallback", 2, "knowledge_hybrid_search", "READ_ONLY", false,
                        "{\"query\":\"ChatClient\"}", List.of(releaseId),
                        "ERROR_CODE_MATCH", "[\"TOOL_TIMEOUT\"]")), now);
        checkpoints.recordRevision(plan.planId(), 2, "FAILURE_BRANCH",
                "{\"nodes\":2}", now.plusSeconds(1));

        assertThat(checkpoints.requestPause(
                ACTOR.workspaceId(), ACTOR.userId(), runId, now.plusSeconds(2))).isTrue();
        assertThat(checkpoints.control(runId))
                .isEqualTo(AgentCheckpointStore.ControlState.PAUSE_REQUESTED);
        UUID checkpointId = UUID.randomUUID();
        AgentCheckpointStore.Checkpoint checkpoint = checkpoints.save(
                new AgentCheckpointStore.CheckpointDraft(
                        checkpointId, plan.planId(), runId, ACTOR.workspaceId(), ACTOR.userId(),
                        "SAFE_POINT", "{\"evidence\":[\"release\"]}",
                        "{\"usedNodes\":1}", now.plusSeconds(3)));
        checkpoints.markPaused(plan.planId(), checkpointId, now.plusSeconds(3));
        runStore.pauseRun(runId, "", now.plusSeconds(3));
        assertThat(checkpoints.control(runId)).isEqualTo(AgentCheckpointStore.ControlState.PAUSED);
        assertThat(checkpointQuery.latest(ACTOR, runId)).get()
                .extracting("id", "status").containsExactly(checkpointId, "AVAILABLE");

        AgentRunQuery.RunDetail paused = runQuery.findRun(ACTOR, runId).orElseThrow();
        assertThat(paused.status()).isEqualTo("PAUSED");
        assertThat(paused.plan().nodes()).hasSize(2);
        assertThat(paused.plan().nodes().get(1).conditionType()).isEqualTo("ERROR_CODE_MATCH");
        assertThat(paused.plan().nodes().get(1).expectedErrorCodes()).containsExactly("TOOL_TIMEOUT");
        assertThat(paused.plan().nodes().get(1).revision()).isEqualTo(2);

        UUID resumedRunId = UUID.randomUUID();
        runStore.startRun(ACTOR, resumedRunId, null, "p21b-resume-" + resumedRunId,
                "从检查点继续", now.plusSeconds(4));
        AgentOrchestrationStore.PlanHandle resumedPlan = orchestration.startRun(
                resumedRunId, new AgentOrchestrationStore.RunLimits(
                        12, 3, 8, 16_000, new BigDecimal("0.500000")), now.plusSeconds(4));
        assertThat(checkpoints.consume(checkpoint.id(), resumedRunId, now.plusSeconds(5))).isTrue();
        checkpoints.linkResume(resumedPlan.planId(), checkpoint.id(), now.plusSeconds(5));
        assertThat(jdbcClient.sql("select resumed_from_checkpoint_id from agent_plan where id = :id")
                .param("id", resumedPlan.planId()).query(UUID.class).single())
                .isEqualTo(checkpoint.id());
        assertThat(checkpoints.consume(checkpoint.id(), UUID.randomUUID(), now.plusSeconds(6))).isFalse();
        assertThat(checkpointQuery.latest(ACTOR, runId)).get()
                .extracting("status", "resumedRunId").containsExactly("CONSUMED", resumedRunId);
    }

    @Test
    @Order(17)
    void workspaceCostReservationSettlementAndHardLimitAreAuditable() {
        JdbcAgentCostGovernanceStore costs = new JdbcAgentCostGovernanceStore(jdbcClient);
        Instant now = Instant.parse("2026-08-22T05:00:00Z");
        LocalDate day = LocalDate.of(2026, 8, 22);
        YearMonth month = YearMonth.of(2026, 8);
        AgentCostGovernanceStore.Policy policy = costs.ensurePolicy(
                ACTOR.workspaceId(), new AgentCostGovernanceStore.DefaultPolicy(
                        10_000, new BigDecimal("10"), 100_000, new BigDecimal("100"),
                        3, 80, true), now);
        assertThat(policy.version()).isEqualTo(1);

        UUID runId = UUID.randomUUID();
        runStore.startRun(ACTOR, runId, null, "p21c-cost-" + runId,
                "验证成本预占与结算", now);
        AgentCostGovernanceStore.ReservationRequest request =
                new AgentCostGovernanceStore.ReservationRequest(
                        UUID.randomUUID(), runId, ACTOR.workspaceId(), ACTOR.userId(),
                        2_000, new BigDecimal("2.000000"), day, month, now);
        AgentCostGovernanceStore.ReservationDecision reserved = costs.reserve(request);
        assertThat(reserved.allowed()).isTrue();
        assertThat(reserved.reservation().status()).isEqualTo("RESERVED");
        assertThat(costs.reserve(request).reservation().id())
                .isEqualTo(reserved.reservation().id());

        costs.settle(runId, 1_500, new BigDecimal("1.250000"), now.plusSeconds(1));
        costs.settle(runId, 9_999, new BigDecimal("9.999999"), now.plusSeconds(2));
        AgentCostGovernanceStore.Overview settled = costs.overview(
                ACTOR.workspaceId(), day, month, 20);
        assertThat(settled.usage().dailyTokens()).isEqualTo(1_500);
        assertThat(settled.usage().dailyCostCny()).isEqualByComparingTo("1.250000");
        assertThat(settled.usage().activeReservations()).isZero();
        assertThat(settled.ledger()).extracting(AgentCostGovernanceStore.LedgerEntry::entryType)
                .contains("RESERVE", "SETTLE");

        costs.updatePolicy(ACTOR.workspaceId(), ACTOR.userId(),
                new AgentCostGovernanceStore.PolicyUpdate(
                        true, 1_600, new BigDecimal("2"), 2_000, new BigDecimal("3"),
                        1, 75, true), now.plusSeconds(3));
        UUID rejectedRunId = UUID.randomUUID();
        runStore.startRun(ACTOR, rejectedRunId, null, "p21c-reject-" + rejectedRunId,
                "验证硬配额拒绝", now.plusSeconds(3));
        AgentCostGovernanceStore.ReservationDecision rejected = costs.reserve(
                new AgentCostGovernanceStore.ReservationRequest(
                        UUID.randomUUID(), rejectedRunId, ACTOR.workspaceId(), ACTOR.userId(),
                        200, new BigDecimal("1.000000"), day, month, now.plusSeconds(4)));
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.reason()).isEqualTo("DAILY_TOKEN_LIMIT");
        assertThat(rejected.reservation().status()).isEqualTo("REJECTED");
        assertThat(costs.overview(ACTOR.workspaceId(), day, month, 20).ledger())
                .extracting(AgentCostGovernanceStore.LedgerEntry::entryType).contains("REJECT");
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
