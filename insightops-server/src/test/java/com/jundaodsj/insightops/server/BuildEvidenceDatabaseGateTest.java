package com.jundaodsj.insightops.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcBuildEvidenceStore;
import com.jundaodsj.insightops.infrastructure.persistence.JdbcReportReviewStore;
import com.jundaodsj.insightops.report.application.BuildEvidence.Batch;
import com.jundaodsj.insightops.report.application.BuildEvidence.Counts;
import com.jundaodsj.insightops.server.report.BuildEvidenceService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "INSIGHTOPS_CHAIN_GATE", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BuildEvidenceDatabaseGateTest {
    private static final ActorContext OWNER = new ActorContext(
            UUID.fromString("00000000-0000-0000-0000-000000000101"), UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final String XML = """
            <testsuite name="example.Orders" tests="2" failures="0" errors="1" skipped="0">
              <testcase classname="example.Orders" name="loadsOwner"/>
              <testcase classname="example.Orders" name="savesVisit"><error type="example.ContextError" message="上下文未加载">Orders.java:18</error></testcase>
            </testsuite>
            """;
    private final String schema = "io05_import_gate_" + UUID.randomUUID().toString().replace("-", "");
    private JdbcClient root, jdbc;
    private boolean createdSchema;
    private TransactionTemplate tx;
    private JdbcBuildEvidenceStore store;
    private BuildEvidenceService service;

    @BeforeAll void migrate() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:55432/insightops");
        String username = System.getenv().getOrDefault("DB_USERNAME", "insightops");
        String password = System.getenv().getOrDefault("DB_PASSWORD", "insightops_dev");
        root = JdbcClient.create(new DriverManagerDataSource(url, username, password));
        root.sql("create schema " + schema).update();
        createdSchema = true;
        var db = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, username, password);
        Flyway.configure().dataSource(db).schemas(schema).defaultSchema(schema).load().migrate();
        jdbc = JdbcClient.create(db);
        tx = new TransactionTemplate(new DataSourceTransactionManager(db));
        store = new JdbcBuildEvidenceStore(jdbc, new ObjectMapper().findAndRegisterModules());
        service = new BuildEvidenceService(new JdbcReportReviewStore(jdbc), store, new ObjectMapper());
    }

    @AfterAll void releaseOwnSchema() {
        if (createdSchema) root.sql("drop schema " + schema + " cascade").update();
    }

    @Test void concurrentTransactionsShareOneReceiptAndReturnOriginalBytesAfterCommit() throws Exception {
        UUID system = system(OWNER), report = report(OWNER, system), request = UUID.randomUUID();
        var parts = parts(XML);
        String declaration = declaration(request, "BASELINE");
        var firstInserted = new CompletableFuture<Batch>();
        var commit = new CountDownLatch(1);
        var secondPid = new CompletableFuture<Integer>();
        Batch original;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> tx.execute(t -> {
                var batch = service.importBatch(OWNER, report, declaration, parts);
                firstInserted.complete(batch);
                try {
                    if (!commit.await(25, TimeUnit.SECONDS)) throw new AssertionError("Concurrent import did not reach its database lock");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return batch;
            }));
            firstInserted.get(10, TimeUnit.SECONDS);
            var second = pool.submit(() -> tx.execute(t -> {
                secondPid.complete(jdbc.sql("select pg_backend_pid()").query(Integer.class).single());
                return service.importBatch(OWNER, report, declaration, parts);
            }));
            try {
                int pid = secondPid.get(10, TimeUnit.SECONDS);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                boolean waiting = false;
                while (System.nanoTime() < deadline && !waiting) {
                    waiting = jdbc.sql("select cardinality(pg_blocking_pids(?)) > 0").param(pid).query(Boolean.class).single();
                    if (!waiting) Thread.sleep(25);
                }
                assertThat(waiting).as("second backend waits on the first transaction's uncommitted unique receipt").isTrue();
            } finally {
                commit.countDown();
            }
            original = first.get(10, TimeUnit.SECONDS);
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(original);
        }
        UUID batchId = original.summary().id();
        assertThat(service.list(OWNER, report)).containsExactly(original.summary());
        assertThat(service.require(OWNER, report, batchId)).isEqualTo(original);
        assertThat(original.summary().counts()).isEqualTo(new Counts(1, 0, 1, 0));
        assertThat(original.files()).hasSize(2);
        for (var file : original.files()) {
            var bytes = file.name().endsWith(".xml") ? XML.getBytes(StandardCharsets.UTF_8) : logBytes();
            var downloaded = service.download(OWNER, report, batchId, file.id());
            assertThat(downloaded.content()).isEqualTo(bytes);
            assertThat(downloaded.file()).isEqualTo(file);
            assertThat(file.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            assertThat(file.size()).isEqualTo(bytes.length);
        }
        assertThat(jdbc.sql("select count(*) from build_evidence_file where batch_id=?").param(batchId).query(Integer.class).single()).isEqualTo(2);
        assertThat(save(OWNER, report, declaration, parts)).isEqualTo(original);
        status(HttpStatus.CONFLICT, () -> save(OWNER, report, declaration, parts(XML.replace("Orders.java:18", "Orders.java:28"))));
        status(HttpStatus.CONFLICT, () -> save(OWNER, report, declaration(request, "CANDIDATE"), parts));
        UUID anotherReport = report(OWNER, system);
        status(HttpStatus.CONFLICT, () -> save(OWNER, anotherReport, declaration, parts));
        assertThat(service.require(OWNER, report, batchId)).isEqualTo(original);
    }

    @Test void reactorArchivesIdenticalFileNamesUnderDifferentModules() {
        UUID system = system(OWNER), report = report(OWNER, system);
        var declaration = """
                {"requestId":"%s","role":"BASELINE","modules":[
                  {"path":"alpha","projectName":"alpha","selected":true},
                  {"path":"beta","projectName":"beta","selected":true}]}
                """.formatted(UUID.randomUUID());
        var parts = Map.<String, List<MultipartFile>>of(
                "moduleXml0", List.of(new MockMultipartFile("moduleXml0", "TEST-example.Orders.xml", "text/xml",
                        XML.getBytes(StandardCharsets.UTF_8))),
                "moduleXml1", List.of(new MockMultipartFile("moduleXml1", "TEST-example.Orders.xml", "text/xml",
                        XML.getBytes(StandardCharsets.UTF_8))));
        var batch = save(OWNER, report, declaration, parts);
        assertThat(batch.files()).extracting(file -> file.modulePath()).containsExactly("alpha", "beta");
        assertThat(batch.modules()).extracting(module -> module.counts()).containsExactly(new Counts(1, 0, 1, 0), new Counts(1, 0, 1, 0));
        assertThat(service.require(OWNER, report, batch.summary().id())).isEqualTo(batch);
        assertThat(save(OWNER, report, declaration, parts)).isEqualTo(batch);
        for (var file : batch.files()) {
            var download = service.download(OWNER, report, batch.summary().id(), file.id());
            assertThat(download.file()).isEqualTo(file);
            assertThat(download.content()).isEqualTo(XML.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test void realReportOwnershipSystemScopeAndCompositeDatabaseKeyRejectMismatches() {
        UUID system = system(OWNER), report = report(OWNER, system);
        var otherOwner = new ActorContext(UUID.randomUUID(), OWNER.workspaceId());
        jdbc.sql("insert into app_user(id,username,display_name) values(?,?,'gate reviewer')")
                .params(otherOwner.userId(), "reviewer-" + otherOwner.userId()).update();
        var otherWorkspace = new ActorContext(OWNER.userId(), UUID.randomUUID());
        jdbc.sql("insert into workspace(id,name,slug) values(?,'gate workspace',?)")
                .params(otherWorkspace.workspaceId(), otherWorkspace.workspaceId().toString()).update();
        for (var actor : List.of(otherOwner, otherWorkspace)) {
            jdbc.sql("insert into workspace_member(workspace_id,user_id,role) values(?,?,'MEMBER')")
                    .params(actor.workspaceId(), actor.userId()).update();
        }
        UUID request = UUID.randomUUID();
        String declaration = declaration(request, "BASELINE");
        var parts = parts(XML);
        var original = save(OWNER, report, declaration, parts);
        UUID batch = original.summary().id(), file = original.files().getFirst().id();
        UUID sameSystemReport = report(OWNER, system);
        assertThat(service.require(OWNER, sameSystemReport, batch)).isEqualTo(original);
        assertThat(service.list(OWNER, sameSystemReport)).containsExactly(original.summary());
        assertThat(service.download(OWNER, sameSystemReport, batch, file).file().id()).isEqualTo(file);

        for (var actor : List.of(otherOwner, otherWorkspace)) {
            status(HttpStatus.NOT_FOUND, () -> service.list(actor, report));
            status(HttpStatus.NOT_FOUND, () -> service.require(actor, report, batch));
            status(HttpStatus.NOT_FOUND, () -> service.download(actor, report, batch, file));
            status(HttpStatus.NOT_FOUND, () -> save(actor, report, declaration, parts));
            assertThat(store.request(actor, system, request)).isEmpty();
            assertThat(store.original(actor, system, batch, file)).isEmpty();
            assertThatThrownBy(() -> jdbc.sql("update build_evidence_batch set workspace_id=?,owner_user_id=? where id=?")
                    .params(actor.workspaceId(), actor.userId(), batch).update()).isInstanceOf(DataIntegrityViolationException.class);
        }
        for (var actor : List.of(OWNER, otherOwner, otherWorkspace)) {
            UUID differentSystem = system(actor), differentReport = report(actor, differentSystem);
            assertThat(service.list(actor, differentReport)).isEmpty();
            status(HttpStatus.NOT_FOUND, () -> service.require(actor, differentReport, batch));
            status(HttpStatus.NOT_FOUND, () -> service.download(actor, differentReport, batch, file));
            var independent = save(actor, differentReport, declaration, parts);
            assertThat(independent.summary().id()).isNotEqualTo(batch);
            status(HttpStatus.NOT_FOUND, () -> service.download(actor, differentReport, independent.summary().id(), file));
        }
        assertThat(service.require(OWNER, report, batch)).isEqualTo(original);
    }

    @Test void transactionRollbackRemovesReceiptAndAllOriginalsAndAllowsRetry() {
        UUID system = system(OWNER), report = report(OWNER, system), request = UUID.randomUUID();
        var parts = parts(XML);
        String declaration = declaration(request, "CANDIDATE");
        var rolledBack = tx.execute(t -> {
            var batch = service.importBatch(OWNER, report, declaration, parts);
            assertThat(service.require(OWNER, report, batch.summary().id())).isEqualTo(batch);
            t.setRollbackOnly();
            return batch;
        });
        UUID previous = rolledBack.summary().id();
        assertThat(service.list(OWNER, report)).isEmpty();
        assertThat(store.request(OWNER, system, request)).isEmpty();
        assertThat(jdbc.sql("select count(*) from build_evidence_file where batch_id=?").param(previous).query(Integer.class).single()).isZero();
        var committed = save(OWNER, report, declaration, parts);
        assertThat(committed.summary().id()).isNotEqualTo(previous);
        assertThat(service.require(OWNER, report, committed.summary().id()).files()).hasSize(2);
    }

    private Batch save(ActorContext actor, UUID report, String declaration, Map<String, List<MultipartFile>> parts) {
        return tx.execute(t -> service.importBatch(actor, report, declaration, parts));
    }

    private static void status(HttpStatus expected, ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(expected));
    }

    private static String declaration(UUID request, String role) {
        return "{\"role\":\"" + role + "\",\"requestId\":\"" + request + "\"}";
    }

    private static byte[] logBytes() {
        return "[INFO] Tests run: 2, Failures: 0, Errors: 1, Skipped: 0\r\n[INFO] BUILD FAILURE\r\n".getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, List<MultipartFile>> parts(String xml) {
        return Map.of("xmlFiles", List.of(new MockMultipartFile("xmlFiles", "TEST-Orders.xml", "application/xml", xml.getBytes(StandardCharsets.UTF_8))),
                "mavenLog", List.of(new MockMultipartFile("mavenLog", "out.log", "text/plain", logBytes())));
    }

    private UUID system(ActorContext actor) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into business_system(id,workspace_id,owner_user_id,name,purpose,key_processes,technology_stack,current_problems,constraints_text)
                values(?,?,?,'DB gate fixture','','','','','')
                """).params(id, actor.workspaceId(), actor.userId()).update();
        return id;
    }

    private UUID report(ActorContext actor, UUID system) {
        UUID run = UUID.randomUUID(), report = UUID.randomUUID();
        String inputs = "{\"assessmentType\":\"SYSTEM_DECISION\",\"systemSnapshot\":{\"id\":\"" + system + "\",\"revision\":1}}";
        jdbc.sql("insert into agent_run(id,workspace_id,owner_user_id,trace_id,status,question) values(?,?,?,?,'SUCCEEDED','DB gate')")
                .params(run, actor.workspaceId(), actor.userId(), run.toString()).update();
        jdbc.sql("""
                insert into agent_workflow_run(run_id,workspace_id,owner_user_id,business_system_id,template_name_snapshot,
                    template_version_snapshot,entry_question_snapshot,graph_spec_snapshot,input_snapshot,tool_contract_fingerprint,request_id,created_at)
                values(?,?,?,?,'DB gate',1,'DB gate','{}',cast(? as jsonb),'DB gate',?,now())
                """).params(run, actor.workspaceId(), actor.userId(), system, inputs, UUID.randomUUID()).update();
        jdbc.sql("""
                insert into research_report(id,workspace_id,user_id,title,report_type,period_start,period_end,item_count,high_risk_count,
                    snapshot,markdown_content,source_run_id,research_snapshot)
                values(?,?,?,'DB gate','RESEARCH',now()-interval '1 second',now(),1,0,'{}','DB gate',?,cast(? as jsonb))
                """).params(report, actor.workspaceId(), actor.userId(), run, "{\"runId\":\"" + run + "\",\"inputs\":" + inputs + "}").update();
        return report;
    }
}
