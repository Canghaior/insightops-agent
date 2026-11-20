package com.jundaodsj.insightops.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jundaodsj.insightops.agent.application.AgentWorkflowTemplateStore;
import com.jundaodsj.insightops.businesssystem.application.BusinessSystemStore.Background;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.infrastructure.delivery.DeliverySecretCipher;
import com.jundaodsj.insightops.infrastructure.persistence.*;
import com.jundaodsj.insightops.knowledge.application.KnowledgeStore;
import com.jundaodsj.insightops.knowledge.application.KnowledgeUploadStore;
import com.jundaodsj.insightops.server.businesssystem.BusinessSystemService;
import com.jundaodsj.insightops.server.chat.DurableChatRunCoordinator;
import com.jundaodsj.insightops.server.chat.DurableChatRunProperties;
import com.jundaodsj.insightops.server.report.ReportPdfRenderer;
import com.jundaodsj.insightops.server.report.ReportService;
import com.jundaodsj.insightops.server.report.ReportReviewService;
import com.jundaodsj.insightops.report.application.ReportReviewStore.*;
import com.jundaodsj.insightops.server.report.ResearchReportService;
import com.jundaodsj.insightops.server.tool.AgentToolRegistryConfiguration;
import com.jundaodsj.insightops.server.workflow.*;
import com.jundaodsj.insightops.tool.application.registry.AgentToolDefinition.AccessLevel;
import com.jundaodsj.insightops.tool.application.registry.AgentToolRegistry;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.jundaodsj.insightops.infrastructure.config.DeepSeekModelProperties;
import com.jundaodsj.insightops.infrastructure.config.ResearchModelCatalog;
import com.jundaodsj.insightops.model.application.ResearchModel;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "INSIGHTOPS_CHAIN_GATE", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BusinessSystemDatabaseGateTest {
    private static final UUID WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final ActorContext ACTOR = new ActorContext(USER, WORKSPACE);
    private final String schema = "io01_gate_" + UUID.randomUUID().toString().replace("-", "");
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private DriverManagerDataSource root;
    private JdbcClient jdbc;
    private TransactionTemplate tx;
    private BusinessSystemService systems;
    private UpgradeAssessmentService upgrades;
    private SystemDecisionAssessmentService decisions;
    private AgentWorkflowRunService runs;
    private JdbcAgentWorkflowRunStore workflows;
    private JdbcKnowledgeUploadStore uploads;
    private ResearchReportService research;
    private ReportService exports;
    private ReportReviewService review;

    @BeforeAll void setup() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:55432/insightops");
        String user = System.getenv().getOrDefault("DB_USERNAME", "insightops");
        String password = System.getenv().getOrDefault("DB_PASSWORD", "insightops_dev");
        root = new DriverManagerDataSource(url, user, password);
        var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
        var flyway = Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).createSchemas(true);
        flyway.target("43").load().migrate();
        jdbc = JdbcClient.create(source);
        UUID legacy = UUID.randomUUID();
        jdbc.sql("insert into agent_run(id,workspace_id,owner_user_id,trace_id,status,question) values(?, ?, ?, ?, 'RUNNING','legacy')")
                .params(legacy, WORKSPACE, USER, legacy.toString()).update();
        jdbc.sql("""
                insert into agent_workflow_run(run_id,workspace_id,owner_user_id,template_name_snapshot,
                    template_version_snapshot,entry_question_snapshot,graph_spec_snapshot,input_snapshot,
                    tool_contract_fingerprint,request_id,created_at)
                values(?,?,?,'legacy',1,'legacy','{}','{"businessProject":"订单系统"}','old',?,now())
                """).params(legacy, WORKSPACE, USER, UUID.randomUUID()).update();
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
        workflows = new JdbcAgentWorkflowRunStore(jdbc);
        var historical = workflows.find(legacy).orElseThrow();
        assertThat(historical.businessSystemId()).isNull();
        assertThat(historical.requestedModel()).isNull();
        assertThat(historical.inputJson()).contains("订单系统").doesNotContain("systemSnapshot");

        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        uploads = new JdbcKnowledgeUploadStore(jdbc);
        var materials = new ResearchMaterials(uploads);
        systems = new BusinessSystemService(new JdbcBusinessSystemStore(jdbc), materials);
        var templates = new JdbcAgentWorkflowTemplateStore(jdbc);
        var registry = new AgentToolRegistry(AgentToolRegistryConfiguration.definitions(true, true));
        var runQuery = new JdbcAgentRunQuery(jdbc, json);
        var queue = new DurableChatRunCoordinator(new JdbcDurableChatRunStore(jdbc), null, new DurableChatRunProperties(), json);
        runs = new AgentWorkflowRunService(templates, workflows, new JdbcChatRunStore(jdbc, null, json),
                runQuery, queue, new AgentWorkflowExpressionService(json, registry), registry, json,
                new ResearchModelCatalog(new DeepSeekModelProperties(true, "", "deepseek-v4-flash", false,
                        .2, 1024, 2, 30, 0, false), "mock-openai", "mock-deepseek", ""));
        var watches = new JdbcUserProjectWatchStore(jdbc);
        watches.setEnabled(ACTOR, PROJECT, true, Instant.now());
        upgrades = new UpgradeAssessmentService(templates, runs, watches, materials, null, registry, json,
                null, null, mock(KnowledgeStore.class), null, systems);
        decisions = new SystemDecisionAssessmentService(systems, materials, watches, templates, runs, json, registry);
        var reports = new JdbcReportDeliveryStore(jdbc, json, new DeliverySecretCipher("io01-report-fixture-only"));
        research = new ResearchReportService(runQuery, workflows, reports, json, "http://localhost");
        exports = new ReportService(reports, new ReportPdfRenderer(""));
        review = new ReportReviewService(new JdbcReportReviewStore(jdbc), materials);
    }

    @AfterAll void cleanup() {
        if (root != null) JdbcClient.create(root).sql("drop schema if exists " + schema + " cascade").update();
    }

    private BusinessSystemService.Draft draft(String stack, Integer revision, List<UUID> files) {
        return new BusinessSystemService.Draft(new Background("订单系统", "订单履约", "支付回调", stack,
                "偶发重复通知", "上线窗口两小时"), files, revision);
    }

    private UUID saveFixtureReport(UUID run) {
        jdbc.sql("update agent_run set status='SUCCEEDED', answer='验收样例：建议核对字体。', finished_at=now() where id=?")
                .param(run).update();
        return research.create(ACTOR, run).id();
    }

    private ReportReviewService.VerificationCommand blocked(UUID request, List<UUID> uploads) {
        var fields = new VerificationInput("中点是否可见？", "下一步验证第 3 项", Origin.HUMAN_OR_TOOL,
                Execution.BLOCKED, "", "", "", Impact.INCONCLUSIVE, "仅样本", "原阅读器版本未知", uploads);
        return new ReportReviewService.VerificationCommand(request, null, fields);
    }

    @Test void immutableVerificationVersionsAndDecisionBasisSurviveReplayAndProfileChange() throws Exception {
        var system = create("字体实验", List.of());
        var run = decide(system.id(), 1, UUID.randomUUID(), "中点留白").runId();
        var report = saveFixtureReport(run);
        var frozen = exports.find(ACTOR,report);
        UUID upload = file("SUCCEEDED");
        var command = blocked(UUID.randomUUID(), List.of(upload));
        Verification first;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var calls = java.util.stream.IntStream.range(0,2).mapToObj(i -> pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return tx.execute(t -> review.record(ACTOR,report,command));
            })).toList();
            start.countDown();
            first = calls.get(0).get(10,TimeUnit.SECONDS);
            assertThat(calls.get(1).get(10,TimeUnit.SECONDS)).isEqualTo(first);
        }
        var dc = new ReportReviewService.DecisionCommand(UUID.randomUUID(),
                new DecisionInput("暂缓","缺少原阅读器","取得版本后复核",List.of(first.id())));
        var decision = tx.execute(t -> review.decide(ACTOR,report,dc));
        tx.executeWithoutResult(t -> systems.update(ACTOR,system.id(), draft("新环境",1,List.of())));
        var executed = new VerificationInput("中点是否可见？","下一步验证第 3 项",Origin.MODEL,Execution.EXECUTED,
                "同正文导出","Poppler 120 DPI","中点可见",Impact.SUPPORTS,"仅此环境，根因待核对","",List.of(upload));
        var correction = new ReportReviewService.VerificationCommand(UUID.randomUUID(),first.id(),executed);
        var second = tx.execute(t -> review.record(ACTOR,report,correction));
        assertThat(second.version()).isEqualTo(2);
        assertThat(second.previousVersionId()).isEqualTo(first.id());
        Verification replayed = tx.execute(t -> review.record(ACTOR,report,correction));
        assertThat(replayed).isEqualTo(second);
        assertThatThrownBy(() -> tx.execute(t -> review.record(ACTOR,report,
                new ReportReviewService.VerificationCommand(UUID.randomUUID(),first.id(),executed)))).hasMessageContaining("409");
        Decision replayedDecision = tx.execute(t -> review.decide(ACTOR,report,dc));
        assertThat(replayedDecision).isEqualTo(decision);
        var changed = new ReportReviewService.DecisionCommand(dc.requestId(),
                new DecisionInput("改变选择","新理由","待复核",List.of(second.id())));
        assertThatThrownBy(() -> tx.execute(t -> review.decide(ACTOR,report,changed))).hasMessageContaining("409");
        var history = review.history(ACTOR, report);
        assertThat(history.context().systemRevision()).isEqualTo(1);
        assertThat(history.context().runId()).isEqualTo(run);
        assertThat(history.context().requestedModel()).isNull();
        assertThat(history.verifications()).containsExactly(first,second);
        assertThat(history.decisions()).containsExactly(decision);
        assertThat(history.decisions().getFirst().fields().verificationVersionIds()).containsExactly(first.id());
        assertThat(exports.find(ACTOR,report)).isEqualTo(frozen);
        assertThat(exports.markdown(ACTOR,report)).isEqualTo(frozen.markdown());
        try (var pdf = Loader.loadPDF(exports.pdf(ACTOR,report))) {
            assertThat(new PDFTextStripper().getText(pdf)).contains("字体实验").doesNotContain("中点可见","原阅读器版本未知","新环境");
        }
    }

    @Test void verificationRejectsWrongOwnerWorkspaceReportUploadAndForgedOrigins() {
        var system = create("scope",List.of());
        UUID one = saveFixtureReport(decide(system.id(),1,UUID.randomUUID(),"一").runId());
        UUID two = saveFixtureReport(decide(system.id(),1,UUID.randomUUID(),"二").runId());
        var cmd = blocked(UUID.randomUUID(),List.of());
        var first = tx.execute(t -> review.record(ACTOR, one, cmd));
        for (var actor : List.of(new ActorContext(UUID.randomUUID(),WORKSPACE), new ActorContext(USER,UUID.randomUUID()))) {
            assertThatThrownBy(() -> review.history(actor,one)).hasMessageContaining("404");
            assertThatThrownBy(() -> tx.execute(t -> review.record(actor,one,cmd))).hasMessageContaining("404");
        }
        assertThatThrownBy(() -> tx.execute(t -> review.record(ACTOR,two,
                new ReportReviewService.VerificationCommand(UUID.randomUUID(),first.id(),cmd.fields()))))
                .isInstanceOf(IllegalArgumentException.class);
        var wrong = new ReportReviewService.DecisionCommand(UUID.randomUUID(),new DecisionInput("暂缓","待复核","有资料时",List.of(first.id())));
        assertThatThrownBy(() -> tx.execute(t -> review.decide(ACTOR,two,wrong))).isInstanceOf(IllegalArgumentException.class);
        assertThat(review.history(ACTOR,two).decisions()).isEmpty();
        assertThatThrownBy(() -> tx.execute(t -> review.record(ACTOR,two,blocked(UUID.randomUUID(),List.of(UUID.randomUUID())))))
                .isInstanceOf(IllegalArgumentException.class);
        var upgrade = saveFixtureReport(launch(ACTOR,system.id(),1,UUID.randomUUID()).runId());
        assertThatThrownBy(() -> review.history(ACTOR,upgrade)).hasMessageContaining("404");
        jdbc.sql("update research_report set research_snapshot=jsonb_set(research_snapshot,'{runId}',to_jsonb('wrong'::text)) where id=?")
                .param(two).update();
        assertThatThrownBy(() -> tx.execute(t -> review.record(ACTOR,two,cmd))).hasMessageContaining("404");
    }

    private BusinessSystemService.Detail create(String stack, List<UUID> files) {
        return tx.execute(status -> systems.create(ACTOR, draft(stack, null, files)));
    }

    private AgentWorkflowRunService.LaunchResult launch(ActorContext actor, UUID id, Integer revision, UUID requestId) {
        var command = new UpgradeAssessmentService.Command(PROJECT, "1.0.0", "1.1.0", "", "", "任意客户端名称",
                List.of("API 与配置变更"), List.of(), List.of(), requestId);
        return tx.execute(status -> upgrades.launch(actor, false, AccessLevel.WORKSPACE_MEMBER, command, id, revision, requestId.toString()));
    }

    private UUID file(String status) {
        UUID id = UUID.randomUUID();
        uploads.create(new KnowledgeUploadStore.CreateUpload(id, UUID.randomUUID(), WORKSPACE, PROJECT, USER,
                "回调说明.txt", id + ".txt", "text/plain", 40, "a".repeat(64), "PRIVATE", 100000), Instant.now());
        jdbc.sql("update knowledge_upload set status=? where id=?").params(status, id).update();
        return id;
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void upgradesExistingTemplateOnceAndRetainsItsGraph(boolean previouslyActivated) throws Exception {
        var store = new JdbcAgentWorkflowTemplateStore(jdbc);
        var initial = tx.execute(status -> upgrades.setup(ACTOR));
        var current = store.find(WORKSPACE, initial.templateId()).orElseThrow().versions().getFirst();
        var oldGraph = (ObjectNode) json.readTree(current.graphSpecJson());
        var inputs = (ObjectNode) oldGraph.get("inputs");
        inputs.remove(List.of("systemSnapshot", "selectedMaterials"));
        oldGraph.put("reason", "systemSnapshot");
        var old = tx.execute(status -> {
            var version = store.createVersion(WORKSPACE, initial.templateId(), USER,
                    new AgentWorkflowTemplateStore.VersionDraft("旧版", current.entryQuestion(), oldGraph.toString()),
                    Instant.now()).versions().getFirst();
            if (previouslyActivated) {
                store.activate(WORKSPACE, initial.templateId(), version.id(), USER, "旧模板样本", Instant.now());
            } else {
                jdbc.sql("update agent_workflow_template_version set status='RETIRED' where id=?").param(initial.versionId()).update();
                jdbc.sql("update agent_workflow_template set active_version_id=null where id=?").param(initial.templateId()).update();
            }
            return version;
        });

        var upgraded = tx.execute(status -> upgrades.setup(ACTOR));
        assertThat(upgraded.versionId()).isNotEqualTo(old.id());
        assertThat(tx.execute(status -> upgrades.setup(ACTOR)).versionId()).isEqualTo(upgraded.versionId());
        var versions = store.find(WORKSPACE, initial.templateId()).orElseThrow().versions();
        var active = versions.getFirst();
        assertThat(active.version()).isEqualTo(old.version() + 1);
        var contract = json.readTree(active.graphSpecJson()).path("inputs");
        assertThat(contract.path("systemSnapshot").path("type").asText()).isEqualTo("json");
        assertThat(contract.path("selectedMaterials").path("type").asText()).isEqualTo("json_array");
        assertThat(versions.stream().filter(v -> v.id().equals(old.id())).findFirst().orElseThrow().graphSpecJson())
                .isEqualTo(old.graphSpecJson());
    }

    @Test void scopedReadWriteLaunchHistoryAndAttachmentSelection() {
        var file = file("SUCCEEDED");
        var profile = create("JDK17", List.of(file, file));
        assertThat(systems.detail(ACTOR, profile.id())).isEqualTo(profile);
        assertThat(profile.attachments()).singleElement().satisfies(f -> assertThat(f.name()).isEqualTo("回调说明.txt"));
        assertThat(systems.list(ACTOR)).anyMatch(item -> item.id().equals(profile.id()));
        for (var other : List.of(new ActorContext(UUID.randomUUID(), WORKSPACE), new ActorContext(USER, UUID.randomUUID()))) {
            assertThat(systems.list(other)).isEmpty();
            assertThat(systems.availableUploads(other)).noneMatch(f -> f.uploadId().equals(file));
            assertThatThrownBy(() -> systems.detail(other, profile.id())).isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(() -> systems.update(other, profile.id(), draft("伪造", 1, List.of()))).isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(() -> launch(other, profile.id(), 1, UUID.randomUUID())).isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(() -> systems.history(other, profile.id(), 0)).isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(() -> systems.create(other, draft("test", null, List.of(file)))).hasMessageContaining("无权访问");
        }
        assertThatThrownBy(() -> create("JDK", List.of(file("PENDING")))).hasMessageContaining("尚未处理完成");
        jdbc.sql("update knowledge_upload set status='FAILED' where id=?").param(file).update();
        assertThatThrownBy(() -> launch(ACTOR, profile.id(), 1, UUID.randomUUID())).hasMessageContaining("尚未处理完成");
        assertThat(systems.history(ACTOR, profile.id(), 0).items()).isEmpty();
    }

    @Test void removalOfALinkedUploadChangesTheDossierRevisionAndPreservesTheRun() {
        var file = file("SUCCEEDED");
        var system = create("JDK17", List.of(file));
        var run = launch(ACTOR, system.id(), 1, UUID.randomUUID());
        tx.executeWithoutResult(status -> uploads.delete(WORKSPACE, USER, false, file).orElseThrow());
        var current = systems.detail(ACTOR, system.id());
        assertThat(current.revision()).isEqualTo(2);
        assertThat(current.attachments()).isEmpty();
        assertThatThrownBy(() -> launch(ACTOR, system.id(), 1, UUID.randomUUID())).hasMessageContaining("409");
        assertThat(workflows.find(run.runId()).orElseThrow().inputJson()).contains(file.toString(), "回调说明.txt");
    }

    @Test void reportCreatedAfterEditUsesR1AndExportsPreserveTheSavedReport() throws Exception {
        var file = file("SUCCEEDED");
        var profile = create("Stack-R1", List.of(file));
        var id = profile.id();
        UUID request = UUID.randomUUID();
        UUID r1 = launch(ACTOR, id, 1, request).runId();
        var old = workflows.find(r1).orElseThrow();
        assertThat(old.businessSystemId()).isEqualTo(id);
        assertThat(old.inputJson()).contains("Stack-R1", "回调说明.txt").doesNotContain("任意客户端名称");
        assertThat(old.graphSpecJson()).contains("systemSnapshot", "team_material");
        assertThat(jdbc.sql("select contextual_prompt from agent_run_work where run_id=?").param(r1).query(String.class).single())
                .contains("Stack-R1", "支付回调", "上线窗口两小时", "回调说明.txt");
        tx.executeWithoutResult(status -> systems.update(ACTOR, id, draft("Stack-R2", 1, List.of())));
        assertThat(systems.detail(ACTOR, id).revision()).isEqualTo(2);
        assertThatThrownBy(() -> systems.update(ACTOR, id, draft("stale", 1, List.of(file)))).hasMessageContaining("409");
        assertThatThrownBy(() -> launch(ACTOR, id, 1, UUID.randomUUID())).hasMessageContaining("409");
        var duplicate = launch(ACTOR, id, 1, request);
        assertThat(duplicate.duplicate()).isTrue();
        assertThat(duplicate.runId()).isEqualTo(r1);
        assertThatThrownBy(() -> launch(ACTOR, create("other", List.of()).id(), 1, request))
                .isInstanceOf(AgentWorkflowRunService.RequestConflict.class);
        UUID r2 = launch(ACTOR, id, 2, UUID.randomUUID()).runId();
        assertThat(workflows.find(r2).orElseThrow().inputJson()).contains("Stack-R2").doesNotContain("Stack-R1", "回调说明.txt");
        assertThat(systems.history(ACTOR, id, 0).items()).extracting("runId").containsExactly(r2, r1);

        // A deterministic terminal answer substitutes for model execution in this database gate.
        jdbc.sql("update agent_run set status='SUCCEEDED', answer='合成验收回答', finished_at=now() where id=?").param(r1).update();
        var report = research.create(ACTOR, r1);
        assertThat(report.research().inputs()).containsEntry("systemSnapshot", json.readValue(old.inputJson(), Map.class).get("systemSnapshot"));
        assertThat(report.markdown()).contains("业务系统背景（用户提供）", "Stack-R1", "回调说明.txt").doesNotContain("Stack-R2", "systemSnapshot", "selectedMaterials");
        tx.executeWithoutResult(status -> systems.update(ACTOR, id, draft("Stack-R3", 2, List.of())));
        assertThat(research.create(ACTOR, r1).id()).isEqualTo(report.id());
        assertThat(exports.markdown(ACTOR, report.id())).isEqualTo(report.markdown());
        try (var pdf = Loader.loadPDF(exports.pdf(ACTOR, report.id()))) {
            assertThat(new PDFTextStripper().getText(pdf)).contains("Stack-R1").doesNotContain("Stack-R2", "Stack-R3");
        }
        var legacy = launch(ACTOR, null, null, UUID.randomUUID());
        assertThat(workflows.find(legacy.runId()).orElseThrow().inputJson()).doesNotContain("systemSnapshot");
        assertThat(systems.history(ACTOR, id, 0).items()).hasSize(2);
    }

    private AgentWorkflowRunService.LaunchResult decide(UUID systemId, int revision, UUID requestId, String observation) {
        var command = new SystemDecisionAssessmentService.Command(requestId, "回调变慢应先做什么？", observation, "", null, List.of());
        return tx.execute(status -> decisions.launch(ACTOR, false, AccessLevel.WORKSPACE_MEMBER, systemId, revision, command, requestId.toString()));
    }

    @Test void modelSelectionSurvivesReplayQueueAndRetry() throws Exception {
        var id = create("Java 21", List.of()).id();
        UUID request = UUID.randomUUID();
        var original = new SystemDecisionAssessmentService.Command(request, "合成问题", "", "", null, List.of(), "gpt-6.2-sol");
        var run = tx.execute(status -> decisions.launch(ACTOR, false, AccessLevel.WORKSPACE_MEMBER, id, 1, original, UUID.randomUUID().toString()));
        var changed = new SystemDecisionAssessmentService.Command(request, "other", "", "", null, List.of(), "deepseek-v4-pro-thinking");
        var replay = tx.execute(status -> decisions.launch(ACTOR, false, AccessLevel.WORKSPACE_MEMBER, id, 1, changed, UUID.randomUUID().toString()));
        assertThat(replay.runId()).isEqualTo(run.runId());
        assertThat(workflows.find(run.runId()).orElseThrow().requestedModel()).isEqualTo("gpt-6.2-sol");

        var token = UUID.randomUUID();
        jdbc.sql("update agent_run_work set status='RUNNING', attempt_count=1, lease_token=?, lease_expires_at=now()+interval '1 minute' where run_id=?")
                .params(token, run.runId()).update();
        var queue = new JdbcDurableChatRunStore(jdbc);
        assertThat(tx.execute(status -> queue.prepareAttempt(run.runId(), token, Instant.now())).researchModel())
                .isEqualTo(ResearchModel.GPT_62_SOL);
        jdbc.sql("update agent_run set status='FAILED', finished_at=now() where id=?").param(run.runId()).update();
        jdbc.sql("update agent_workflow_run_node set status='FAILED' where run_id=?").param(run.runId()).update();
        var retry = tx.execute(status -> runs.retry(ACTOR, false, AccessLevel.WORKSPACE_MEMBER, run.runId(), "review_inputs", UUID.randomUUID(), UUID.randomUUID().toString()));
        assertThat(workflows.find(retry.runId()).orElseThrow().requestedModel()).isEqualTo("gpt-6.2-sol");
        var fresh = new SystemDecisionAssessmentService.Command(UUID.randomUUID(), "合成问题", "", "", null, List.of(), "deepseek-v4-pro-thinking");
        var next = tx.execute(status -> decisions.launch(ACTOR, false, AccessLevel.WORKSPACE_MEMBER, id, 1, fresh, UUID.randomUUID().toString()));
        assertThat(workflows.find(next.runId()).orElseThrow().requestedModel()).isEqualTo("deepseek-v4-pro-thinking");
        jdbc.sql("update agent_run set status='SUCCEEDED',answer='合成回答',finished_at=now(),model_provider='deepseek',model_name='deepseek-v4-pro' where id=?")
                .param(next.runId()).update();
        var report = research.create(ACTOR, next.runId());
        assertThat(report.research().requestedModel()).isEqualTo("deepseek-v4-pro-thinking");
        assertThat(exports.markdown(ACTOR, report.id())).contains("选择的模型：DeepSeek Pro · 思考", "响应模型：deepseek-v4-pro");
        try (var pdf = Loader.loadPDF(exports.pdf(ACTOR, report.id()))) {
            assertThat(new PDFTextStripper().getText(pdf)).contains("DeepSeek Pro", "deepseek-v4-pro");
        }
    }

    @Test void decisionsFreezeInputsAndKeepBothKindsOfRunsDistinct() throws Exception {
        var system = create("Stack-before", List.of());
        UUID id = system.id(), request = UUID.randomUUID();
        var first = decide(id, 1, request, "旧自述");
        var stored = workflows.find(first.runId()).orElseThrow();
        assertThat(stored.nodes()).extracting("toolName").containsExactly("decision_input_review");
        assertThat(stored.inputJson()).contains("SYSTEM_DECISION", "Stack-before", "旧自述")
                .doesNotContain("projectId", "currentVersion", "targetVersion");
        assertThat(jdbc.sql("select contextual_prompt from agent_run_work where run_id=?").param(first.runId()).query(String.class).single())
                .contains("回调变慢应先做什么", "旧自述", "Stack-before", "没有外部资料依据", "下一步验证");

        tx.executeWithoutResult(status -> systems.update(ACTOR, id, draft("Stack-after", 1, List.of())));
        assertThat(decide(id, 1, request, "另一个问题背景").runId()).isEqualTo(first.runId());
        assertThatThrownBy(() -> decide(id, 1, UUID.randomUUID(), "")).hasMessageContaining("409");
        assertThatThrownBy(() -> launch(ACTOR, id, 2, request)).isInstanceOf(AgentWorkflowRunService.RequestConflict.class);
        UUID upgradeRequest = UUID.randomUUID();
        var upgrade = launch(ACTOR, id, 2, upgradeRequest);
        assertThatThrownBy(() -> decide(id, 2, upgradeRequest, "")).isInstanceOf(AgentWorkflowRunService.RequestConflict.class);
        // Simulate a genuine legacy upgrade snapshot missing the type.
        jdbc.sql("update agent_workflow_run set input_snapshot=input_snapshot-'assessmentType' where run_id=?").param(upgrade.runId()).update();
        var second = decide(id, 2, UUID.randomUUID(), "新自述");
        assertThat(workflows.find(second.runId()).orElseThrow().inputJson()).contains("Stack-after", "新自述").doesNotContain("旧自述");
        var history = systems.history(ACTOR, id, 0).items();
        assertThat(history).extracting(item -> item.assessmentType().name())
                .containsExactly("SYSTEM_DECISION", "UPGRADE_ASSESSMENT", "SYSTEM_DECISION");
        assertThat(history.getFirst().question()).isEqualTo("回调变慢应先做什么？");
        assertThat(history.get(1).currentVersion()).isEqualTo("1.0.0");

        jdbc.sql("update agent_run set status='SUCCEEDED', answer='合成回答：建议先收集回调记录。', finished_at=now() where id=?")
                .param(first.runId()).update();
        var report = research.create(ACTOR, first.runId());
        assertThat(report.title()).contains("系统决策评估", "回调变慢").doesNotContain("→");
        assertThat(report.research().evidenceQuality().status()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(report.research().citations()).isEmpty();
        assertThat(exports.markdown(ACTOR, report.id())).contains("系统决策评估", "旧自述", "Stack-before", "没有外部资料依据")
                .doesNotContain("Stack-after", "→", "<decision_inputs>", "assessmentType", "decisionQuestion");
        try (var pdf = Loader.loadPDF(exports.pdf(ACTOR, report.id()))) {
            assertThat(new PDFTextStripper().getText(pdf)).contains("系统决策评估", "Stack-before").doesNotContain("Stack-after");
        }
        jdbc.sql("update agent_run set status='FAILED', finished_at=now() where id=?").param(second.runId()).update();
        jdbc.sql("update agent_workflow_run_node set status='FAILED' where run_id=?").param(second.runId()).update();
        tx.executeWithoutResult(status -> systems.update(ACTOR, id, draft("Stack-later", 2, List.of())));
        var retried = tx.execute(status -> runs.retry(ACTOR, false, AccessLevel.WORKSPACE_MEMBER, second.runId(), "review_inputs", UUID.randomUUID(), UUID.randomUUID().toString()));
        assertThat(workflows.find(retried.runId()).orElseThrow().inputJson()).isEqualTo(workflows.find(second.runId()).orElseThrow().inputJson());
    }

    @Test void concurrentDuplicateAndFailedNodeRetryKeepOriginalAssociation() throws Exception {
        var profile = create("Retry-A", List.of());
        UUID request = UUID.randomUUID(), id = profile.id();
        var start = new CountDownLatch(1);
        UUID firstRun;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var submissions = java.util.stream.IntStream.range(0, 2).mapToObj(i -> executor.submit(() -> {
                start.await();
                return launch(ACTOR, id, 1, request);
            })).toList();
            start.countDown();
            var one = submissions.get(0).get(20, TimeUnit.SECONDS);
            var two = submissions.get(1).get(20, TimeUnit.SECONDS);
            assertThat(one.runId()).isEqualTo(two.runId());
            assertThat(one.duplicate()).isNotEqualTo(two.duplicate());
            firstRun = one.runId();
        }
        assertThat(systems.history(ACTOR, id, 0).items()).hasSize(1);
        var source = workflows.find(firstRun).orElseThrow();
        jdbc.sql("update agent_run set status='FAILED', finished_at=now() where id=?").param(firstRun).update();
        jdbc.sql("update agent_workflow_run_node set status='FAILED' where run_id=? and logical_node_id='releases'").param(firstRun).update();
        tx.executeWithoutResult(status -> systems.update(ACTOR, id, draft("Retry-B", 1, List.of())));
        UUID retryId = UUID.randomUUID();
        var child = tx.execute(status -> runs.retry(ACTOR, false, AccessLevel.WORKSPACE_MEMBER, firstRun, "releases", retryId, "retry"));
        var retry = workflows.find(child.runId()).orElseThrow();
        assertThat(retry.businessSystemId()).isEqualTo(id);
        assertThat(retry.inputJson()).isEqualTo(source.inputJson());
        assertThat(retry.entryQuestion()).isEqualTo(source.entryQuestion());
        assertThat(retry.sourceRunId()).isEqualTo(firstRun);
        assertThat(systems.history(ACTOR, id, 0).items()).hasSize(2);
    }
}
