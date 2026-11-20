package com.jundaodsj.insightops.server.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.knowledge.application.KnowledgeFileStorage;
import com.jundaodsj.insightops.knowledge.application.KnowledgeUploadStore;
import com.jundaodsj.insightops.report.application.BuildEvidence.*;
import com.jundaodsj.insightops.report.application.ReportDeliveryStore.ReportRecord;
import com.jundaodsj.insightops.report.application.ReportReviewStore;
import com.jundaodsj.insightops.report.application.ReportReviewStore.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VerificationHandoffServiceTest {
    private final ReportReviewStore reviews = mock(ReportReviewStore.class);
    private final ReportService reports = mock(ReportService.class);
    private final BuildEvidenceService builds = mock(BuildEvidenceService.class);
    private final KnowledgeUploadStore uploads = mock(KnowledgeUploadStore.class);
    private final KnowledgeFileStorage storage = mock(KnowledgeFileStorage.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final VerificationHandoffService service = new VerificationHandoffService(reviews,reports,builds,uploads,storage,json);
    private final ActorContext actor = new ActorContext(UUID.randomUUID(),UUID.randomUUID());
    private final UUID report = UUID.randomUUID(), system = UUID.randomUUID(), run = UUID.randomUUID();
    private final UUID version = UUID.randomUUID(), previous = UUID.randomUUID();
    private final Author author = new Author(actor.userId(),"工程人员");

    @BeforeEach void scopedOriginal() {
        var context = new Context(report,run,system,3,null,null,null);
        when(reviews.context(actor,report,false)).thenReturn(Optional.of(context));
        when(reports.find(actor,report)).thenReturn(new ReportRecord(report,"测试报告","RESEARCH",Instant.EPOCH,
                Instant.EPOCH,List.of(),List.of(),0,0,List.of(),"# 已存报告\r\n原始字节\n",Instant.EPOCH));
        when(reviews.decisions(report)).thenReturn(List.of());
    }

    @Test void exportsTheRequestedVersionWithTraceableBytesAndOnlyItsDecisions() throws Exception {
        Batch left = build(Role.BASELINE,Status.PASSED), right = build(Role.CANDIDATE,Status.ERROR);
        var supplementId = UUID.randomUUID();
        byte[] ownerResult = "Owner request reached expected detail page".getBytes(StandardCharsets.UTF_8);
        allowFile(supplementId,"主人查询.md",ownerResult);
        saveVersion(left,right,List.of(supplementId));
        var relevant = new Decision(UUID.randomUUID(),new DecisionInput("暂缓","已取得报错","定位后再议",List.of(version,previous)),author,Instant.EPOCH);
        var unrelated = new Decision(UUID.randomUUID(),new DecisionInput("其他决定","其他依据","之后",List.of(previous)),author,Instant.EPOCH);
        when(reviews.decisions(report)).thenReturn(List.of(unrelated,relevant));

        var entries = export();
        assertThat(entries.get("report/original.md")).isEqualTo("# 已存报告\r\n原始字节\n".getBytes(StandardCharsets.UTF_8));
        var selected = read(entries,"verification/version.json");
        assertThat(selected.path("id").asText()).isEqualTo(version.toString());
        assertThat(selected.path("buildSummary").asText()).isEqualTo("保存时的摘要");
        assertThat(selected.path("fields").path("executedBy").asText()).isEqualTo("外部执行者");
        assertThat(read(entries,"comparison/diff.json").path("groups").get(0).path("candidate").get(0).path("stack").asText())
                .isEqualTo("at Example.java:9");
        var decisions = read(entries,"decisions.json");
        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).path("fields").path("verificationVersionIds").toString())
                .contains(version.toString(),previous.toString());
        var guide = new String(entries.get("README.md"),StandardCharsets.UTF_8);
        assertThat(guide).contains("外部执行者","保存时的摘要","主人查询正常","暂缓","第 2 版","OwnerTest#find",
                "[`主人查询.md`](<supplementary/" + supplementId).doesNotContain("其他决定");
        assertThat(new String(entries.get("comparison/results.md"),StandardCharsets.UTF_8))
                .contains("OwnerTest#find","testcase 1","执行报错","ExampleError","未知");

        var manifest = read(entries,"manifest.json");
        assertThat(manifest.path("missing")).hasSize(4);
        assertThat(manifest.path("missing").toString()).contains("未导入 Maven 日志", "未导入命令记录");
        assertThat(manifest.path("entries")).hasSize(entries.size()-1);
        for (var record : manifest.path("entries")) {
            byte[] content = entries.get(record.path("path").asText());
            assertThat(content).isNotNull().hasSize(record.path("size").asInt());
            assertThat(sha(content)).isEqualTo(record.path("sha256").asText());
        }
        assertThat(manifest.path("baselineBuildId").asText()).isEqualTo(left.summary().id().toString());
        assertThat(entries.get("supplementary/"+supplementId+"/主人查询.md")).isEqualTo(ownerResult);
        verify(reviews).verification(report,version);
        verify(reviews,never()).verifications(any());
        verify(uploads).findVisible(actor.workspaceId(),actor.userId(),false,supplementId);
    }

    @Test void olderVersionWithNoBuildsStaysUnboundAndLostAttachmentsAreListed() throws Exception {
        UUID hidden = UUID.randomUUID(), lost = UUID.randomUUID();
        saveVersion(null,null,List.of(hidden,lost));
        when(uploads.findVisible(actor.workspaceId(),actor.userId(),false,hidden)).thenReturn(Optional.empty());
        allowFile(lost,"old-private-name.md","lost".getBytes(StandardCharsets.UTF_8));
        when(storage.open(lost+".bin")).thenThrow(new IOException("not readable"));
        var entries = export();
        assertThat(entries.keySet()).noneMatch(p -> p.startsWith("comparison/") || p.startsWith("originals/") || p.startsWith("supplementary/"));
        var gaps = read(entries,"manifest.json").path("missing");
        assertThat(gaps).hasSize(3);
        assertThat(gaps.toString()).contains(hidden.toString(),lost.toString(),"保存时未关联").doesNotContain("old-private-name");
        String overview = new String(entries.get("README.md"), StandardCharsets.UTF_8);
        assertThat(overview).contains("实际执行者（记录者填写）\n\n    未知");
        verify(storage,never()).open(hidden+".bin");
        verifyNoInteractions(builds);
    }

    @Test void wrongReportOrOwnerCannotReadTheVersionOrAnyFile() {
        var outsider = new ActorContext(UUID.randomUUID(),actor.workspaceId());
        assertThatThrownBy(() -> service.prepare(outsider,report,version)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        when(reviews.verification(report,version)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.prepare(actor,report,version)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        verify(reports, never()).find(any(),any());
        verifyNoInteractions(uploads,builds,storage);
    }

    @Test void changedOriginalFailsBeforeAFileCanBeReturned() throws Exception {
        Batch left = build(Role.BASELINE,Status.PASSED), right = build(Role.CANDIDATE,Status.PASSED);
        saveVersion(left,right,List.of());
        var file = right.files().getFirst();
        when(builds.download(actor,report,right.summary().id(),file.id())).thenReturn(
                new Original(file,new byte[(int)file.size()]));
        assertThatThrownBy(() -> service.prepare(actor,report,version)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409").hasMessageContaining("SHA-256");
    }

    @Test void aRollbackBeforeTheControllerReceivesTheFileDisposesOfIt() throws Exception {
        saveVersion(null,null,List.of());
        TransactionSynchronizationManager.initSynchronization();
        try (var archive = service.prepare(actor,report,version)) {
            assertThat(archive.size()).isPositive();
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            assertThatThrownBy(archive::size).isInstanceOf(java.nio.channels.ClosedChannelException.class);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test void userFileNamesRemainDataAndNeverSupplyArchiveDirectories() throws Exception {
        UUID first = UUID.randomUUID(),second = UUID.randomUUID();
        byte[] content = "note".getBytes(StandardCharsets.UTF_8);
        allowFile(first,"../nested/file.md",content);
        allowFile(second,"CON.md",content);
        saveVersion(null,null,List.of(first,second));
        var entries = export();
        assertThat(entries.keySet()).noneMatch(s -> s.contains("..") || s.contains("CON.md") || s.contains("\\"));
        assertThat(entries).containsKeys("supplementary/"+first+"/attachment.md","supplementary/"+second+"/attachment.md");
        var manifest = read(entries,"manifest.json");
        assertThat(manifest.path("entries").toString()).contains("../nested/file.md","CON.md");
        assertThat(HandoffArchiveWriter.leaf("LPT¹.txt","attachment.txt")).isEqualTo("attachment.txt");
        assertThat(HandoffArchiveWriter.leaf("CON .md","attachment.md")).isEqualTo("attachment.md");
        assertThat(HandoffArchiveWriter.leaf("资".repeat(90)+".md","attachment.md")).isEqualTo("attachment.md");
    }

    private void saveVersion(Batch left, Batch right, List<UUID> fileIds) {
        var fields = new VerificationInput("观察到什么","建议第 3 项",Origin.MODEL,Execution.EXECUTED,"执行了回归",
                "隔离环境","主人查询正常",Impact.INCONCLUSIVE,"限本环境","",fileIds,
                left == null ? null : left.summary().id(),right == null ? null : right.summary().id(),left == null ? null : "外部执行者");
        var record = new Verification(version,previous,2,fields,author,Instant.EPOCH,left == null ? null : "保存时的摘要");
        when(reviews.verification(report,version)).thenReturn(Optional.of(record));
    }

    private Batch build(Role role, Status status) throws Exception {
        UUID batchId = UUID.randomUUID(), fileId = UUID.randomUUID();
        var counts = status == Status.PASSED ? new Counts(1,0,0,0) : new Counts(0,0,1,0);
        String xml = "<testsuite name='Service' tests='1' failures='0' errors='"+counts.errors()+"' skipped='0'>"
                + "<testcase classname='OwnerTest' name='find'>"+(status == Status.PASSED ? "" : "<error type='ExampleError'>at Example.java:9</error>")
                + "</testcase></testsuite>";
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        var file = new EvidenceFile(fileId,"TEST-Service.xml",FileKind.SUREFIRE_XML,bytes.length,sha(bytes));
        var tc = new TestCase(fileId,"Service","OwnerTest","find",1,status,
                status == Status.PASSED ? null : "ExampleError",null,status == Status.PASSED ? null : "at Example.java:9");
        var batch = new Batch(new Summary(batchId,system,report,role,"declaration",Instant.EPOCH,counts),
                new Metadata("declaration","H2",null,"external",null,null),
                new BuildRecord(List.of(),null,null,null,null,List.of()),new LogFacts(null,null),
                List.of(),List.of(file),List.of(tc),List.of("缺少日志"));
        when(builds.require(actor,report,batchId)).thenReturn(batch);
        when(builds.download(actor,report,batchId,fileId)).thenReturn(new Original(file,bytes));
        return batch;
    }

    private void allowFile(UUID id, String name, byte[] bytes) throws Exception {
        var row = new KnowledgeUploadStore.UploadRecord(id,UUID.randomUUID(),UUID.randomUUID(),"project",actor.userId(),
                "author",name,"text/markdown",bytes.length,sha(bytes),"PRIVATE","SUCCEEDED",1,null,null,null,null,Instant.EPOCH,Instant.EPOCH);
        when(uploads.findVisible(actor.workspaceId(),actor.userId(),false,id)).thenReturn(Optional.of(row));
        when(storage.open(id+".bin")).thenReturn(new ByteArrayInputStream(bytes));
    }

    private Map<String, byte[]> export() throws Exception {
        var zipped = new ByteArrayOutputStream();
        try (var archive = service.prepare(actor,report,version)) {
            archive.copyTo(zipped);
            assertThat((long)zipped.size()).isEqualTo(archive.size());
        }
        var entries = new HashMap<String,byte[]>();
        try (var input = new ZipInputStream(new ByteArrayInputStream(zipped.toByteArray()), StandardCharsets.UTF_8)) {
            for (var item = input.getNextEntry(); item != null; item = input.getNextEntry()) {
                assertThat(entries.put(item.getName(),input.readAllBytes())).isNull();
            }
        }
        return entries;
    }

    private JsonNode read(Map<String,byte[]> entries,String path) throws IOException { return json.readTree(entries.get(path)); }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
