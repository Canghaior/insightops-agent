package com.jundaodsj.insightops.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.agent.application.AgentRunQuery;
import com.jundaodsj.insightops.conversation.application.ChatRunStore;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import com.jundaodsj.insightops.identity.application.AdminAccountStore;
import com.jundaodsj.insightops.intelligence.application.IntelligenceStore;
import com.jundaodsj.insightops.project.application.ProjectUpdateStore;
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
import com.jundaodsj.insightops.infrastructure.persistence.JdbcProjectUpdateStore;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcIntelligenceStore;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcKnowledgeStore;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcUserProjectWatchStore;
import com.jundaodsj.insightops.infrastructure.knowledge.KnowledgeDocumentChunker;
import com.jundaodsj.insightops.knowledge.application.KnowledgeStore;
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
    private static JdbcProjectUpdateStore projectUpdateStore;
    private static JdbcIntelligenceStore intelligenceStore;
    private static JdbcKnowledgeStore knowledgeStore;

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
        assertThat(migration.migrationsExecuted).isEqualTo(12);

        jdbcClient = JdbcClient.create(dataSource);
        adminAccountStore = new JdbcAdminAccountStore(jdbcClient);
        projectUpdateStore = new JdbcProjectUpdateStore(jdbcClient, objectMapper());
        intelligenceStore = new JdbcIntelligenceStore(jdbcClient, objectMapper());
        knowledgeStore = new JdbcKnowledgeStore(jdbcClient, objectMapper());
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
    void shouldPersistDeduplicateAndReadProjectUpdates() {
        Instant now = Instant.parse("2026-08-17T09:00:00Z");
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
                now.plusSeconds(2), Duration.ofMinutes(5), 3).stream()
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

    @Test
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
        var firstResult = knowledgeStore.completeSuccessfulSync(firstTask, List.of(page),
                firstRun.plusSeconds(2), firstRun.plus(Duration.ofHours(24)));

        assertThat(firstResult.newDocuments()).isEqualTo(1);
        assertThat(firstResult.chunkCount()).isEqualTo(chunks.size());
        var firstStatus = knowledgeStore.sourceStatus(ACTOR.workspaceId()).stream()
                .filter(source -> source.sourceId().equals(sourceId)).findFirst().orElseThrow();
        assertThat(firstStatus.documentCount()).isEqualTo(1);
        assertThat(firstStatus.revisionCount()).isEqualTo(1);
        assertThat(firstStatus.chunkCount()).isEqualTo(chunks.size());

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
