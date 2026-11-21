package com.jundaodsj.insightops.server.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.businesssystem.application.EngineeringBaselineStore;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.infrastructure.report.TemporaryReportArchive;
import com.jundaodsj.insightops.knowledge.application.KnowledgeFileStorage;
import com.jundaodsj.insightops.knowledge.application.KnowledgeUploadStore;
import com.jundaodsj.insightops.report.application.BuildEvidence.*;
import com.jundaodsj.insightops.report.application.EngineeringTaskStore.*;
import com.jundaodsj.insightops.report.application.ReportDeliveryStore.ReportRecord;
import com.jundaodsj.insightops.report.application.ReportDeliveryStore.ResearchSnapshot;
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
    private final EngineeringBaselineStore baselines = mock(EngineeringBaselineStore.class);
    private final EngineeringTaskService tasks = mock(EngineeringTaskService.class);
    private final VerificationHandoffService service = new VerificationHandoffService(reviews,reports,builds,uploads,storage,json,
            new FrozenBaselineHandoff(baselines, json), tasks);
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
        verifyNoInteractions(uploads,builds,storage,baselines);
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

    @Test void baselineOriginalComesFromFrozenIdWithMatchingProvenanceAndSize() throws Exception {
        saveVersion(null,null,List.of());
        var id = UUID.randomUUID();
        byte[] pom = "<project><version>old</version></project>".getBytes(StandardCharsets.UTF_8);
        var snapshot = freeze(id, system, "pom.xml", sha(pom));
        when(baselines.find(actor, system, id)).thenReturn(Optional.of(snapshot));
        when(baselines.original(actor, system, id)).thenReturn(Optional.of(pom));
        var entries = export();
        String path = "engineering-baseline/" + id + "/pom.xml";
        assertThat(entries.get(path)).isEqualTo(pom);
        var manifest = read(entries, "manifest.json");
        assertThat(manifest.at("/engineeringBaseline/id").asText()).isEqualTo(id.toString());
        assertThat(manifest.at("/engineeringBaseline/path").asText()).isEqualTo(path);
        assertThat(manifest.at("/engineeringBaseline/size").asLong()).isEqualTo(pom.length);
        assertThat(manifest.at("/engineeringBaseline/sha256").asText()).isEqualTo(sha(pom));
        assertThat(read(entries,"report/engineering-baseline.json").at("/baseline/id").asText()).isEqualTo(id.toString());
        assertThat(new String(entries.get("README.md"),StandardCharsets.UTF_8)).contains(path,"外部执行","候选构建","v1");
        verify(baselines, never()).list(any(),any());
    }

    @Test void missingInvisibleOrDamagedBaselineBlocksThePreviouslyFrozenDownload() throws Exception {
        saveVersion(null,null,List.of());
        var id = UUID.randomUUID();
        byte[] original = {1,2,3};
        var snapshot = freeze(id, system, "baseline.zip", sha(original));
        assertThatThrownBy(this::export).hasMessageContaining("原件已缺失");
        verify(baselines,never()).original(any(),any(),any());
        when(baselines.find(actor, system, id)).thenReturn(Optional.of(snapshot));
        assertThatThrownBy(this::export).hasMessageContaining("409").hasMessageContaining("原件");
        when(baselines.original(actor, system, id)).thenReturn(Optional.of(new byte[] {3,2,1}));
        assertThatThrownBy(this::export).hasMessageContaining("409").hasMessageContaining("SHA-256");
        freeze(id, system, "baseline.zip", sha(new byte[]{4,5}));
        assertThatThrownBy(this::export).hasMessageContaining("原报告冻结的版本或 SHA-256");
    }

    @Test void malformedAssociationCannotTurnIntoAnUnlinkedLegacyReport() throws Exception {
        saveVersion(null,null,List.of());
        var originalLegacy = export();
        assertThat(read(originalLegacy,"manifest.json").has("engineeringBaseline")).isFalse();
        assertThat(originalLegacy).doesNotContainKey("report/engineering-baseline.json");
        freeze(UUID.randomUUID(), UUID.randomUUID(), "pom.xml", sha(new byte[]{1}));
        assertThatThrownBy(this::export).hasMessageContaining("409").hasMessageContaining("归属");
        reportWithInputs(Map.of("engineeringBaseline", "incomplete"));
        assertThatThrownBy(this::export).hasMessageContaining("409");
        verifyNoInteractions(baselines);
    }

    private EngineeringBaselineStore.Snapshot freeze(UUID id, UUID ownerSystem, String name, String hash) {
        var summary = new EngineeringBaselineStore.Summary(id,ownerSystem,1,"保存版本",Instant.EPOCH);
        var content = new EngineeringBaselineStore.Content("保存版本","","",null,null,List.of(),name,hash);
        var snapshot = new EngineeringBaselineStore.Snapshot(summary,content);
        reportWithInputs(Map.of("engineeringBaseline", json.valueToTree(snapshot)));
        return snapshot;
    }

    @Test void taskPackageIncludesTheExactReportBaselineAndEverySelectedVersionAttachmentAndBuild() throws Exception {
        UUID baselineId = UUID.randomUUID(), taskId = UUID.randomUUID(), note = UUID.randomUUID();
        byte[] oldPom = "<project>revision-one</project>".getBytes(StandardCharsets.UTF_8);
        var snapshot = freeze(baselineId, system, "pom.xml", sha(oldPom));
        when(baselines.find(actor,system,baselineId)).thenReturn(Optional.of(snapshot));
        when(baselines.original(actor,system,baselineId)).thenReturn(Optional.of(oldPom));
        var attachments = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var left = build(Role.BASELINE, Status.PASSED);
        var right = build(Role.CANDIDATE, Status.ERROR);
        saveVersion(left, right, attachments);
        for (int i = 0; i < attachments.size(); i++) allowFile(attachments.get(i), "验证依据-" + i + ".md", new byte[]{(byte)i});
        allowFile(note, "范围.md", new byte[]{9});
        var pinned = task(taskId, baselineId, List.of(version), List.of(note));
        when(tasks.version(actor,report,taskId)).thenReturn(pinned);

        var zip = unzip(service.prepareExecution(actor,report,taskId));
        String folder = "basis/" + version + "/";
        var guide = new String(zip.get("task.md"), StandardCharsets.UTF_8);
        assertThat(guide).contains("允许修改或检查的范围", "只验证主人查询", "停止条件", "返回", "建议提出者", "v1",
                folder + "comparison/results.md", "task/materials/" + note, "仍未定位", "原报告摘录，起始行 1");
        assertThat(new String(zip.get("task/suggestion.md"), StandardCharsets.UTF_8)).isEqualTo(pinned.fields().excerpt());
        assertThat(zip.get("engineering-baseline/" + baselineId + "/pom.xml")).isEqualTo(oldPom);
        assertThat(zip).containsKeys(folder + "version.json", folder + "comparison/baseline.json", folder + "comparison/candidate.json",
                folder + HandoffArchiveWriter.originalPath(Role.BASELINE, left.files().getFirst()),
                folder + HandoffArchiveWriter.originalPath(Role.CANDIDATE, right.files().getFirst()), "task/materials/" + note + "/范围.md");
        for (int i = 0; i < attachments.size(); i++) {
            String path = folder + "supplementary/" + attachments.get(i) + "/验证依据-" + i + ".md";
            assertThat(guide).contains(path);
            assertThat(zip.get(path)).isEqualTo(new byte[]{(byte)i});
        }
        var manifest = read(zip, "manifest.json");
        assertThat(manifest.path("packageType").asText()).isEqualTo("TASK_EXECUTION");
        assertThat(manifest.path("taskVersionId").asText()).isEqualTo(taskId.toString());
        assertThat(manifest.at("/engineeringBaseline/id").asText()).isEqualTo(baselineId.toString());
        assertThat(read(zip, folder + "comparison/diff.json").toString()).contains("Example.java:9", "ERROR");
        String index = new String(zip.get(folder + "materials.md"),StandardCharsets.UTF_8);
        assertThat(index).contains(HandoffArchiveWriter.originalPath(Role.BASELINE, left.files().getFirst()),
                HandoffArchiveWriter.originalPath(Role.CANDIDATE, right.files().getFirst()), "验证依据-3.md");
        assertThat(read(zip,"task/version.json").at("/fields/verificationVersionIds/0").asText()).isEqualTo(version.toString());
        verify(baselines, never()).list(any(),any());
        verify(reviews, never()).verifications(any());
    }

    @Test void resultPackageKeepsItsTaskAndVerificationVersionsAndReturnsTheRecordedPatch() throws Exception {
        UUID initialId = UUID.randomUUID(), newerId = UUID.randomUUID(), returnedNote = UUID.randomUUID();
        var initial = task(initialId, null, List.of(previous), List.of());
        when(tasks.version(actor,report,initialId)).thenReturn(initial);
        var originalFields = new VerificationInput("旧观察", "保存于先前验证", Origin.HUMAN_OR_TOOL, Execution.BLOCKED, "",
                "", "", Impact.INCONCLUSIVE, "仍无法执行", "缺少 JDK", List.of());
        var basis = new Verification(previous, null, 1, originalFields, author, Instant.EPOCH, null);
        when(reviews.verification(report,previous)).thenReturn(Optional.of(basis));
        var bytes = "diff --git a/pom.xml b/pom.xml\n".getBytes(StandardCharsets.UTF_8);
        var info = new PatchInfo("candidate.patch", bytes.length, sha(bytes));
        var result = new TaskResult(initialId, version, Instant.EPOCH, "基线标签", "候选标签", "未验证其他方法", info, author, Instant.EPOCH);
        when(tasks.resultForVerification(actor,report,version)).thenReturn(Optional.of(result));
        when(tasks.patch(actor,report,version)).thenReturn(bytes);
        saveVersion(build(Role.BASELINE, Status.PASSED), build(Role.CANDIDATE, Status.ERROR), List.of(returnedNote));
        allowFile(returnedNote, "返回记录.md", new byte[]{7});
        var relevant = new Decision(UUID.randomUUID(),new DecisionInput("暂缓合并","此版报错","定位后重议",List.of(version)),author,Instant.EPOCH);
        var other = new Decision(UUID.randomUUID(),new DecisionInput("其他决定","其他版本","之后",List.of(previous)),author,Instant.EPOCH);
        when(reviews.decisions(report)).thenReturn(List.of(other, relevant));
        // A new task edition exists. Export still follows the result's stored key.
        when(tasks.version(actor,report,newerId)).thenReturn(task(newerId, null, List.of(), List.of()));

        var zip = export();
        assertThat(zip.get("execution/patch/candidate.patch")).isEqualTo(bytes);
        assertThat(new String(zip.get("result.md"), StandardCharsets.UTF_8)).contains(initialId.toString(), version.toString(),
                "实际执行者", "外部执行者", "记录者", "基线标签", "候选标签", "仍无法判断", "未验证其他方法", "candidate.patch", sha(bytes));
        assertThat(new String(zip.get("task.md"), StandardCharsets.UTF_8)).contains("没有关联工程基线", previous.toString());
        assertThat(read(zip,"task/version.json").path("id").asText()).isEqualTo(initialId.toString());
        assertThat(read(zip,"execution/result.json").path("verificationVersionId").asText()).isEqualTo(version.toString());
        assertThat(read(zip,"manifest.json").path("taskVersionId").asText()).isEqualTo(initialId.toString());
        assertThat(read(zip,"decisions.json").toString()).contains(relevant.id().toString()).doesNotContain(other.id().toString());
        assertThat(zip).containsKeys("basis/" + previous + "/version.json", "supplementary/" + returnedNote + "/返回记录.md");
        assertThat(zip.get("report/original.md")).isEqualTo("# 已存报告\r\n原始字节\n".getBytes(StandardCharsets.UTF_8));
        assertThat(new String(zip.get("README.md"), StandardCharsets.UTF_8)).contains("task.md", "result.md");
        verify(tasks,never()).version(actor,report,newerId);
        verify(reviews,never()).verifications(any());

        when(tasks.patch(actor,report,version)).thenReturn(new byte[]{7});
        assertThatThrownBy(this::export).hasMessageContaining("409").hasMessageContaining("SHA-256");
    }

    @Test void wrongActorWrongFrozenReferenceAndInvisibleEvidenceCannotBeExportedSilently() throws Exception {
        UUID taskId = UUID.randomUUID(), hidden = UUID.randomUUID();
        var outsider = new ActorContext(UUID.randomUUID(),actor.workspaceId());
        assertThatThrownBy(() -> service.prepareExecution(outsider,report,taskId)).hasMessageContaining("404");
        verifyNoInteractions(tasks, uploads, storage);
        when(tasks.version(actor,report,taskId)).thenReturn(task(taskId, UUID.randomUUID(), List.of(), List.of(hidden)));
        assertThatThrownBy(() -> service.prepareExecution(actor,report,taskId)).hasMessageContaining("409");
        verifyNoInteractions(uploads, storage);
        when(tasks.version(actor,report,taskId)).thenReturn(task(taskId, null, List.of(), List.of(hidden)));
        var zip = unzip(service.prepareExecution(actor,report,taskId));
        assertThat(read(zip,"manifest.json").path("missing").toString()).contains(hidden.toString(),"没有可取得");
        assertThat(new String(zip.get("task.md"),StandardCharsets.UTF_8)).contains("本次交接缺项",hidden.toString());
        assertThat(zip.keySet()).noneMatch(p -> p.startsWith("task/materials/"));
        verifyNoInteractions(storage);
        when(tasks.version(actor,report,taskId)).thenReturn(task(taskId, null, List.of(UUID.randomUUID()), List.of()));
        assertThatThrownBy(() -> service.prepareExecution(actor,report,taskId)).hasMessageContaining("404");
    }

    private TaskVersion task(UUID id, UUID baselineId, List<UUID> basis, List<UUID> materials) {
        var fields = new TaskFields(Origin.HUMAN_OR_TOOL, "外部工程师", "# 原报告", Readiness.READY,
                "两例能否复现？", "原始 XML", "只验证主人查询", "其他模块", "见归档", "可能影响查询",
                "仍未定位", "定向测试", "能读到 XML 和退出码", "依赖缺失即停止", "隔离 H2", "", basis, materials);
        return new TaskVersion(id, UUID.randomUUID(), null, 1, fields, report, run, system, 3, baselineId,
                baselineId == null ? null : 1, 1, author, Instant.EPOCH);
    }

    @Test void taskIncludesASelectedEarlierExecutionsPatchWithoutPullingItsWholeTaskGraph() throws Exception {
        UUID taskId = UUID.randomUUID(), earlierId = UUID.randomUUID();
        when(tasks.version(actor,report,taskId)).thenReturn(task(taskId, null, List.of(version), List.of()));
        saveVersion(null,null,List.of());
        byte[] patch = "diff --git a/a b/a\n".getBytes(StandardCharsets.UTF_8);
        var returned = new TaskResult(earlierId, version, Instant.EPOCH, "old", "candidate", "其他模块未运行",
                new PatchInfo("previous.patch", patch.length, sha(patch)), author, Instant.EPOCH);
        when(tasks.resultForVerification(actor,report,version)).thenReturn(Optional.of(returned));
        when(tasks.patch(actor,report,version)).thenReturn(patch);
        var zip = unzip(service.prepareExecution(actor,report,taskId));
        String prefix = "basis/" + version + "/";
        assertThat(zip.get(prefix + "execution/patch/previous.patch")).isEqualTo(patch);
        assertThat(read(zip,prefix + "execution/result.json").path("taskVersionId").asText()).isEqualTo(earlierId.toString());
        assertThat(new String(zip.get(prefix + "materials.md"),StandardCharsets.UTF_8)).contains("execution/patch/previous.patch", sha(patch));
        verify(tasks,never()).version(actor,report,earlierId);
    }

    private void reportWithInputs(Map<String,Object> inputs) {
        var research = new ResearchSnapshot(run,"问题","冻结回答",inputs,List.of(),List.of(),List.of(),List.of(),Instant.EPOCH,Instant.EPOCH);
        when(reports.find(actor,report)).thenReturn(new ReportRecord(report,"冻结报告","RESEARCH",Instant.EPOCH,
                Instant.EPOCH,List.of(),List.of(),0,0,List.of(),"# 原报告",Instant.EPOCH,research));
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
        when(storage.open(id+".bin")).thenAnswer(ignored -> new ByteArrayInputStream(bytes));
    }

    private Map<String, byte[]> export() throws Exception {
        return unzip(service.prepare(actor,report,version));
    }

    private Map<String, byte[]> unzip(TemporaryReportArchive archive) throws Exception {
        var zipped = new ByteArrayOutputStream();
        try (archive) {
            archive.copyTo(zipped);
            assertThat((long)zipped.size()).isEqualTo(archive.size());
        }
        var entries = new HashMap<String,byte[]>();
        try (var input = new ZipInputStream(new ByteArrayInputStream(zipped.toByteArray()), StandardCharsets.UTF_8)) {
            for (var item = input.getNextEntry(); item != null; item = input.getNextEntry()) {
                assertThat(entries.put(item.getName(),input.readAllBytes())).isNull();
            }
        }
        var manifest = read(entries, "manifest.json");
        assertThat(manifest.path("entries")).hasSize(entries.size() - 1);
        for (var entry : manifest.path("entries")) {
            byte[] bytes = entries.get(entry.path("path").asText());
            assertThat(bytes).isNotNull().hasSize(entry.path("size").asInt());
            assertThat(sha(bytes)).isEqualTo(entry.path("sha256").asText());
        }
        return entries;
    }

    private JsonNode read(Map<String,byte[]> entries,String path) throws IOException { return json.readTree(entries.get(path)); }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
