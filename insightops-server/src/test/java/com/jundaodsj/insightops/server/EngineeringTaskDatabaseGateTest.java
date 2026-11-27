package com.jundaodsj.insightops.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.knowledge.application.KnowledgeUploadStore;
import com.jundaodsj.insightops.infrastructure.delivery.DeliverySecretCipher;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcBuildEvidenceStore;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcEngineeringTaskStore;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcKnowledgeUploadStore;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcReportDeliveryStore;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcReportReviewStore;
import com.jundaodsj.insightops.report.application.EngineeringTaskStore.*;
import com.jundaodsj.insightops.report.application.ReportReviewStore.*;
import com.jundaodsj.insightops.server.report.BuildComparisonService;
import com.jundaodsj.insightops.server.report.BuildEvidenceService;
import com.jundaodsj.insightops.server.report.EngineeringTaskService;
import com.jundaodsj.insightops.server.report.EngineeringTaskService.*;
import com.jundaodsj.insightops.server.report.ReportPdfRenderer;
import com.jundaodsj.insightops.server.report.ReportReviewService;
import com.jundaodsj.insightops.server.report.ReportService;
import com.jundaodsj.insightops.server.workflow.ResearchMaterials;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "INSIGHTOPS_CHAIN_GATE", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EngineeringTaskDatabaseGateTest {
    private static final ActorContext OWNER = new ActorContext(UUID.fromString("00000000-0000-0000-0000-000000000101"),
            UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final String EXCERPT = "Run the two owner persistence tests individually.";
    private static final String ANSWER = "Evidence covers this scope only.\n" + EXCERPT;
    private static final String MARKDOWN = "# Saved report\n\n" + ANSWER + "\n";
    private static final Instant EXECUTED_AT = Instant.parse("2026-11-20T03:00:00Z");
    private static final byte[] PATCH = "--- a/pom.xml\n+++ b/pom.xml\n".getBytes(StandardCharsets.UTF_8);

    private final String schema = "io07_task_gate_" + UUID.randomUUID().toString().replace("-", "");
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private JdbcClient root, jdbc;
    private boolean created;
    private TransactionTemplate tx;
    private EngineeringTaskService tasks;
    private ReportReviewService review;
    private ReportService reports;

    @BeforeAll void isolatedDatabase() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:55432/insightops");
        String user = System.getenv().getOrDefault("DB_USERNAME", "insightops");
        String password = System.getenv().getOrDefault("DB_PASSWORD", "insightops_dev");
        root = JdbcClient.create(new DriverManagerDataSource(url, user, password));
        root.sql("create schema " + schema).update();
        created = true;
        var ds = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
        Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).load().migrate();
        jdbc = JdbcClient.create(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var reviews = new JdbcReportReviewStore(jdbc);
        var materials = new ResearchMaterials(new JdbcKnowledgeUploadStore(jdbc));
        var builds = new BuildEvidenceService(reviews, new JdbcBuildEvidenceStore(jdbc, json), json);
        review = new ReportReviewService(reviews, materials, new BuildComparisonService(builds));
        reports = new ReportService(new JdbcReportDeliveryStore(jdbc, json, new DeliverySecretCipher("task-gate-fixture")), new ReportPdfRenderer(""));
        tasks = new EngineeringTaskService(new JdbcEngineeringTaskStore(jdbc, json), reviews, review, reports, materials);
    }

    @AfterAll void releaseOwnSchema() {
        if (created) root.sql("drop schema " + schema + " cascade").update();
    }

    @Test void tasksKeepOriginalRevisionAndExcerptForBothReportTypesAndRejectStaleEdits() throws Exception {
        for (boolean upgrade : List.of(false, true)) {
            var f = fixture(upgrade, true);
            var firstCommand = new TaskCommand(UUID.randomUUID(), null, ready("First question", List.of()));
            var concurrentStart = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(2);
            TaskVersion first;
            try {
                var a = pool.submit(() -> { concurrentStart.await(); return save(f, firstCommand); });
                var b = pool.submit(() -> { concurrentStart.await(); return save(f, firstCommand); });
                concurrentStart.countDown();
                first = a.get(8, TimeUnit.SECONDS);
                assertThat(b.get(8, TimeUnit.SECONDS)).isEqualTo(first);
            } finally {
                pool.shutdownNow();
            }
            assertThat(first.runId()).isEqualTo(f.run());
            assertThat(first.systemId()).isEqualTo(f.system());
            assertThat(first.author().id()).isEqualTo(OWNER.userId());
            assertThat(first.excerptLine()).isEqualTo(4);
            assertThat(first.fields().excerpt()).isEqualTo(EXCERPT);
            assertThat(first.engineeringBaselineId()).isEqualTo(f.baseline());
            assertThat(first.engineeringBaselineVersion()).isEqualTo(1);
            jdbc.sql("update business_system set revision=revision+1 where id=?").param(f.system()).update();
            baseline(f.system(), 2);
            var more = new TaskCommand(UUID.randomUUID(), first.id(), ready("Adjusted scope", List.of()));
            var v2 = save(f, more);
            assertThat(v2.taskId()).isEqualTo(first.taskId());
            assertThat(v2.version()).isEqualTo(2);
            assertThat(v2.systemRevision()).isEqualTo(1);
            assertThat(v2.engineeringBaselineId()).isEqualTo(f.baseline());
            assertThat(save(f, firstCommand)).isEqualTo(first);
            status(HttpStatus.CONFLICT, () -> save(f, new TaskCommand(UUID.randomUUID(), first.id(), more.fields())));
            status(HttpStatus.CONFLICT, () -> save(f, new TaskCommand(firstCommand.requestId(), null, more.fields())));
            assertThat(tasks.history(OWNER, f.report()).versions()).containsExactly(first, v2);
            assertThat(reports.find(OWNER, f.report()).markdown()).isEqualTo(MARKDOWN);
        }
    }

    @Test void exactVersionsOwnResultsCorrectionsPatchAndDecisions() throws Exception {
        var f = fixture(false, false);
        var original = reports.find(OWNER, f.report());
        var v1 = save(f, new TaskCommand(UUID.randomUUID(), null, ready("Reproduce", List.of())));
        assertThat(v1.engineeringBaselineId()).isNull();
        var v2 = save(f, new TaskCommand(UUID.randomUUID(), v1.id(), ready("Check another scope", List.of())));
        var cmd = executed(null, false);
        var patch = new PatchFile("candidate.diff", PATCH);
        var initial = record(f, v1, cmd, patch);
        var savedVerification = review.history(OWNER, f.report()).verifications().getFirst();
        assertThat(initial.verificationVersionId()).isEqualTo(savedVerification.id());
        assertThat(savedVerification.fields().executedBy()).isEqualTo("External engineer");
        assertThat(initial.recordedBy().id()).isEqualTo(OWNER.userId());
        assertThat(initial.taskVersionId()).isEqualTo(v1.id());
        assertThat(tasks.patch(OWNER, f.report(), initial.verificationVersionId())).isEqualTo(PATCH);
        assertThat(record(f, v1, cmd, patch)).isEqualTo(initial);
        status(HttpStatus.CONFLICT, () -> record(f, v2, cmd, patch));
        status(HttpStatus.CONFLICT, () -> record(f, v1, cmd, new PatchFile("candidate.diff", "other".getBytes(StandardCharsets.UTF_8))));
        var decision = inTx(() -> review.decide(OWNER, f.report(), new ReportReviewService.DecisionCommand(UUID.randomUUID(),
                new DecisionInput("Defer (fixture)", "Scope is narrow", "Collect more evidence", List.of(savedVerification.id())))));
        var basedOnInitial = save(f, new TaskCommand(UUID.randomUUID(), v2.id(), ready("Use its exact evidence", List.of(savedVerification.id()))));
        var correction = executed(savedVerification.id(), true);
        status(HttpStatus.CONFLICT, () -> record(f, v2, correction, null));
        status(HttpStatus.CONFLICT, () -> inTx(() -> review.record(OWNER, f.report(),
                new ReportReviewService.VerificationCommand(UUID.randomUUID(), savedVerification.id(), savedVerification.fields()))));
        var corrected = record(f, v1, correction, null);
        assertThat(corrected.taskVersionId()).isEqualTo(v1.id());
        assertThat(corrected.patch()).isEqualTo(initial.patch());
        assertThat(tasks.patch(OWNER, f.report(), corrected.verificationVersionId())).isEqualTo(PATCH);
        assertThat(record(f, v1, correction, null)).isEqualTo(corrected);
        assertThat(record(f, v1, cmd, patch)).isEqualTo(initial);
        status(HttpStatus.CONFLICT, () -> record(f, v1, executed(savedVerification.id(), false), null));
        var history = review.history(OWNER, f.report());
        assertThat(history.verifications()).hasSize(2);
        assertThat(history.verifications()).anySatisfy(v -> {
            assertThat(v.id()).isEqualTo(corrected.verificationVersionId());
            assertThat(v.previousVersionId()).isEqualTo(savedVerification.id());
        });
        assertThat(history.decisions()).containsExactly(decision);
        assertThat(decision.fields().verificationVersionIds()).containsExactly(savedVerification.id());
        assertThat(tasks.version(OWNER, f.report(), basedOnInitial.id()).fields().verificationVersionIds()).containsExactly(savedVerification.id());
        assertThat(reports.find(OWNER, f.report())).isEqualTo(original);
        jdbc.sql("update report_engineering_task_result set patch_bytes=? where verification_version_id=?")
                .params(new byte[PATCH.length], corrected.verificationVersionId()).update();
        status(HttpStatus.CONFLICT, () -> tasks.patch(OWNER, f.report(), corrected.verificationVersionId()));
        assertThat(tasks.patch(OWNER, f.report(), savedVerification.id())).isEqualTo(PATCH);
    }

    @Test void provenanceAccessConflictsAndRollbackUseReportScope() throws Exception {
        var a = fixture(false, false);
        var b = fixture(true, false);
        var ready = ready("Scope", List.of());
        var common = new TaskCommand(UUID.randomUUID(), null, ready);
        var v1 = save(a, common);
        var v2 = save(b, common);
        var own = record(a, v1, executed(null, false), null);
        var actors = List.of(new ActorContext(UUID.randomUUID(), OWNER.workspaceId()), new ActorContext(OWNER.userId(), UUID.randomUUID()));
        for (var actor : actors) {
            status(HttpStatus.NOT_FOUND, () -> tasks.history(actor, a.report()));
            status(HttpStatus.NOT_FOUND, () -> tasks.version(actor, a.report(), v1.id()));
            status(HttpStatus.NOT_FOUND, () -> inTx(() -> tasks.save(actor, a.report(), common)));
            status(HttpStatus.NOT_FOUND, () -> inTx(() -> tasks.record(actor, a.report(), v1.id(), executed(null, false), null)));
            status(HttpStatus.NOT_FOUND, () -> tasks.patch(actor, a.report(), own.verificationVersionId()));
        }
        status(HttpStatus.NOT_FOUND, () -> tasks.version(OWNER, b.report(), v1.id()));
        status(HttpStatus.NOT_FOUND, () -> record(b, v1, executed(null, false), null));
        assertThatThrownBy(() -> save(b, new TaskCommand(UUID.randomUUID(), null, ready("Wrong basis", List.of(own.verificationVersionId())))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(tasks.history(OWNER, b.report()).versions()).containsExactly(v2);
        assertThatThrownBy(() -> jdbc.sql("insert into report_engineering_task_basis values(?,?,?)")
                .params(b.report(), v2.id(), own.verificationVersionId()).update()).isInstanceOf(DataIntegrityViolationException.class);
        status(HttpStatus.NOT_FOUND, () -> record(b, v2, executed(own.verificationVersionId(), false), null));
        // An error while persisting the result rolls back the IO-03 verification created in the same transaction.
        jdbc.sql("alter table report_engineering_task_result add constraint test_result_refused check (source_revision <> 'atomic-check')").update();
        var rejected = new ResultCommand(UUID.randomUUID(), null, Execution.EXECUTED, Impact.INCONCLUSIVE, "Run tests","H2",
                "Two errors","Narrow","","External engineer",EXECUTED_AT,"atomic-check","","",List.of(),null,null,false);
        assertThatThrownBy(() -> record(b, v2, rejected, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(review.history(OWNER, b.report()).verifications()).isEmpty();
        assertThat(tasks.history(OWNER, b.report()).results()).isEmpty();
    }

    @Test void plansCorrectionsAndBlockersCanBeRecordedWithoutInventedExecution() throws Exception {
        var f = fixture(false, false);
        var blockedFields = new TaskFields(Origin.HUMAN_OR_TOOL, "Maintainer", "# Saved report", Readiness.NEEDS_MATERIALS,
                "What failed?", "", "", "", "", "", "", "", "", "", "", "Need the candidate patch", List.of(), List.of());
        var plan = save(f, new TaskCommand(UUID.randomUUID(), null, blockedFields));
        assertThat(plan.fields().originDetail()).isEqualTo("Maintainer");
        var blocked = new ResultCommand(UUID.randomUUID(), null, Execution.BLOCKED, Impact.INCONCLUSIVE, "", "", "",
                "", "Dependency unavailable", null, null, "", "", "Retry when available", List.of(),null,null,false);
        var result = record(f, plan, blocked, null);
        assertThat(result.executedAt()).isNull();
        assertThat(result.patch()).isNull();
        assertThat(review.history(OWNER, f.report()).verifications().getFirst().fields().impact()).isEqualTo(Impact.INCONCLUSIVE);
        var pending = new ResultCommand(UUID.randomUUID(), null, Execution.NOT_EXECUTED, Impact.INCONCLUSIVE, "Run H2 tests", "",
                "", "", "", null, null, "", "", "", List.of(), null, null, false);
        assertThat(record(f, plan, pending, null).executedAt()).isNull();
        var bad = new ResultCommand(UUID.randomUUID(), null, Execution.BLOCKED, Impact.SUPPORTS, "", "", "unseen", "", "blocked",
                "", null, "", "", "", List.of(),null,null,false);
        assertThatThrownBy(() -> record(f, plan, bad, null)).isInstanceOf(IllegalArgumentException.class);
        var missingExecutor = new ResultCommand(UUID.randomUUID(), null, Execution.EXECUTED, Impact.INCONCLUSIVE, "run","H2",
                "looked","Only here","",null,null,"src","","",List.of(),null,null,false);
        assertThatThrownBy(() -> record(f, plan, missingExecutor, null)).isInstanceOf(IllegalArgumentException.class);
        var forgedModelQuote = new TaskFields(Origin.MODEL, "", "# Saved report", Readiness.NEEDS_MATERIALS,
                "What?", "", "", "", "", "", "", "", "", "", "", "Need data", List.of(), List.of());
        assertThatThrownBy(() -> save(f, new TaskCommand(UUID.randomUUID(), null, forgedModelQuote))).isInstanceOf(IllegalArgumentException.class);
        var forgedModelName = new TaskFields(Origin.MODEL, "invented model", EXCERPT, Readiness.NEEDS_MATERIALS,
                "What?", "", "", "", "", "", "", "", "", "", "", "Need data", List.of(), List.of());
        assertThatThrownBy(() -> save(f, new TaskCommand(UUID.randomUUID(), null, forgedModelName))).isInstanceOf(IllegalArgumentException.class);
        var badReady = new TaskFields(Origin.HUMAN_OR_TOOL, "Maintainer", "# Saved report", Readiness.READY,
                "Question without a method", "", "", "", "", "", "", "", "", "", "", "", List.of(), List.of());
        assertThatThrownBy(() -> save(f, new TaskCommand(UUID.randomUUID(), null, badReady))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> record(f, plan, executed(null, false), new PatchFile("../unsafe.diff", PATCH)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> record(f, plan, executed(null, false), new PatchFile("invalid.patch", new byte[]{0,-1})))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(review.history(OWNER, f.report()).verifications()).hasSize(2);
        assertThat(review.history(OWNER, f.report()).decisions()).isEmpty();
    }

    @Test void unavailableUploadsRejectANewAssociationButKeepSavedTaskAndReceipt() throws Exception {
        var f = fixture(false, false);
        UUID upload = UUID.randomUUID();
        var files = new JdbcKnowledgeUploadStore(jdbc);
        var externalProject = UUID.fromString("00000000-0000-0000-0000-000000000101");
        inTx(() -> files.create(new KnowledgeUploadStore.CreateUpload(upload, UUID.randomUUID(), OWNER.workspaceId(),
                externalProject, OWNER.userId(), "support.txt", upload + ".txt", "text/plain", 10, "a".repeat(64),
                "PRIVATE", 100000), Instant.now()));
        jdbc.sql("update knowledge_upload set status='SUCCEEDED' where id=?").param(upload).update();
        var fields = new TaskFields(Origin.MODEL, "", EXCERPT, Readiness.NEEDS_MATERIALS, "Check evidence",
                "", "", "", "", "", "", "", "", "", "", "Missing log", List.of(), List.of(upload));
        var draft = new TaskCommand(UUID.randomUUID(), null, fields);
        var old = save(f, draft);
        jdbc.sql("update knowledge_upload set status='FAILED' where id=?").param(upload).update();
        assertThat(save(f, draft)).isEqualTo(old);
        assertThatThrownBy(() -> save(f, new TaskCommand(UUID.randomUUID(), old.id(), fields)))
                .isInstanceOf(IllegalArgumentException.class);
        var updated = save(f, new TaskCommand(UUID.randomUUID(), old.id(), ready("Keep original scope", List.of())));
        assertThat(updated.fields().uploadIds()).isEmpty();
        assertThat(tasks.version(OWNER, f.report(), old.id()).fields().uploadIds()).containsExactly(upload);
    }

    private TaskVersion save(Fixture f, TaskCommand command) {
        return inTx(() -> tasks.save(OWNER, f.report(), command));
    }

    private TaskResult record(Fixture f, TaskVersion task, ResultCommand cmd, PatchFile file) {
        return inTx(() -> tasks.record(OWNER, f.report(), task.id(), cmd, file));
    }

    private <T> T inTx(Supplier<T> action) { return tx.execute(status -> action.get()); }

    private static void status(HttpStatus expected, ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(expected));
    }

    private static TaskFields ready(String question, List<UUID> basis) {
        return new TaskFields(Origin.MODEL, "", EXCERPT, Readiness.READY, question, "Two logs", "Two tests", "Other tests",
                "A prior build exists", "The scope might matter", "Cause", "Run twice", "Logs are available",
                "Stop on dependency failure", "Isolated Java and H2", "", basis, List.of());
    }

    private static ResultCommand executed(UUID previous, boolean keepPatch) {
        return new ResultCommand(UUID.randomUUID(), previous, Execution.EXECUTED, Impact.INCONCLUSIVE,
                "Run selected tests", "JDK 21 / H2", "Two errors", "Selected methods only", "",
                "External engineer", EXECUTED_AT, "baseline-sha (declared)", "candidate patch (declared)",
                "Full suite not run", List.of(), null, null, keepPatch);
    }

    private record Fixture(UUID report, UUID run, UUID system, UUID baseline) {}

    private UUID baseline(UUID system, int version) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into engineering_baseline(id,workspace_id,owner_user_id,system_id,version,label,request_id,
                    request_hash,parsed_content,original_bytes) values(?,?,?,?,?,'fixture',?,repeat('1',64),'{}',?)
                """).params(id, OWNER.workspaceId(), OWNER.userId(), system, version, UUID.randomUUID(), new byte[]{1}).update();
        return id;
    }

    private Fixture fixture(boolean upgrade, boolean withBaseline) throws Exception {
        UUID system = UUID.randomUUID(), run = UUID.randomUUID(), report = UUID.randomUUID();
        jdbc.sql("""
                insert into business_system(id,workspace_id,owner_user_id,name,purpose,key_processes,technology_stack,current_problems,constraints_text)
                values(?,?,?,'Engineering task fixture','','','','','')
                """).params(system, OWNER.workspaceId(), OWNER.userId()).update();
        var inputs = new HashMap<String,Object>();
        inputs.put("systemSnapshot", Map.of("id", system, "revision", 1));
        inputs.put("assessmentType", upgrade ? "UPGRADE_ASSESSMENT" : "SYSTEM_DECISION");
        if (upgrade) {
            inputs.put("projectId", UUID.randomUUID());
            inputs.put("currentVersion", "1.0");
            inputs.put("targetVersion", "1.1");
        }
        UUID baseline = withBaseline ? baseline(system, 1) : null;
        if (withBaseline) inputs.put("engineeringBaseline", Map.of("baseline", Map.of("id", baseline, "systemId", system, "version", 1)));
        jdbc.sql("insert into agent_run(id,workspace_id,owner_user_id,trace_id,status,question) values(?,?,?,?,'SUCCEEDED','A question')")
                .params(run, OWNER.workspaceId(), OWNER.userId(), run.toString()).update();
        jdbc.sql("""
                insert into agent_workflow_run(run_id,workspace_id,owner_user_id,business_system_id,template_name_snapshot,
                    template_version_snapshot,entry_question_snapshot,graph_spec_snapshot,input_snapshot,tool_contract_fingerprint,request_id,created_at)
                values(?,?,?,?,?,1,'Question','{}',cast(? as jsonb),'fixture',?,now())
                """).params(run, OWNER.workspaceId(), OWNER.userId(), system, upgrade ? "升级评估（版本范围）" : "fixture",
                        json.writeValueAsString(inputs), UUID.randomUUID()).update();
        jdbc.sql("""
                insert into research_report(id,workspace_id,user_id,title,report_type,period_start,period_end,item_count,high_risk_count,
                    snapshot,markdown_content,source_run_id,research_snapshot)
                values(?,?,?,'Engineering task fixture','RESEARCH',now()-interval '1 second',now(),1,0,'[]',?,?,cast(? as jsonb))
                """).params(report, OWNER.workspaceId(), OWNER.userId(), MARKDOWN, run,
                        json.writeValueAsString(Map.of("runId",run,"inputs",inputs,"question","A question","answer",ANSWER))).update();
        return new Fixture(report, run, system, baseline);
    }
}
