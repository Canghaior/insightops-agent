package com.jundaodsj.insightops.server.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.infrastructure.report.TemporaryReportArchive;
import com.jundaodsj.insightops.knowledge.application.KnowledgeFileStorage;
import com.jundaodsj.insightops.knowledge.application.KnowledgeUploadStore;
import com.jundaodsj.insightops.report.application.BuildEvidence.*;
import com.jundaodsj.insightops.report.application.BuildEvidence.Module;
import com.jundaodsj.insightops.report.application.BuildEvidenceComparison;
import com.jundaodsj.insightops.report.application.EngineeringTaskStore.TaskResult;
import com.jundaodsj.insightops.report.application.EngineeringTaskStore.TaskVersion;
import com.jundaodsj.insightops.report.application.ReportReviewStore;
import com.jundaodsj.insightops.report.application.ReportReviewStore.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class VerificationHandoffService {
    private final ReportReviewStore reviews;
    private final ReportService reports;
    private final BuildEvidenceService builds;
    private final KnowledgeUploadStore uploads;
    private final KnowledgeFileStorage storage;
    private final FrozenBaselineHandoff frozenBaseline;
    private final EngineeringTaskService tasks;
    private final ObjectWriter json;

    public VerificationHandoffService(ReportReviewStore reviews, ReportService reports, BuildEvidenceService builds,
                                      KnowledgeUploadStore uploads, KnowledgeFileStorage storage, ObjectMapper json,
                                      FrozenBaselineHandoff frozenBaseline, EngineeringTaskService tasks) {
        this.reviews = reviews;
        this.reports = reports;
        this.builds = builds;
        this.uploads = uploads;
        this.storage = storage;
        this.json = json.copy()
                .addMixIn(EvidenceFile.class, OptionalModulePath.class)
                .addMixIn(BuildEvidenceComparison.Group.class, OptionalModulePath.class)
                .addMixIn(BuildEvidenceComparison.class, OptionalModuleComparisons.class)
                .writer(new DefaultPrettyPrinter().withObjectIndenter(new DefaultIndenter("  ", "\n")));
        this.frozenBaseline = frozenBaseline;
        this.tasks = tasks;
    }

    record Missing(String category, String reference, String reason) { }
    record Manifest(int formatVersion, Instant exportedAt, Context report, UUID verificationVersionId, int version,
                    UUID baselineBuildId, UUID candidateBuildId, List<Missing> missing,
                    List<HandoffArchiveWriter.Entry> entries,
                    @JsonInclude(JsonInclude.Include.NON_NULL) FrozenBaselineHandoff.Original engineeringBaseline,
                    @JsonInclude(JsonInclude.Include.NON_NULL) UUID taskVersionId) { }
    private record ExecutionManifest(int formatVersion, String packageType, Instant exportedAt, Context report,
                                     UUID taskId, UUID taskVersionId, int version,
                                     FrozenBaselineHandoff.Original engineeringBaseline, List<Missing> missing,
                                     List<HandoffArchiveWriter.Entry> entries) { }
    private record ParsedImport(Summary summary, Metadata userDeclarations, BuildRecord commandRecord, LogFacts log,
                                List<ExcludedMethod> excludedMethodsFromManifest, List<EvidenceFile> files, List<String> warnings,
                                @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Module> modules) { }

    private interface OptionalModulePath {
        @JsonInclude(JsonInclude.Include.NON_NULL) String modulePath();
    }

    private interface OptionalModuleComparisons {
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<BuildEvidenceComparison.ModuleComparison> modules();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TemporaryReportArchive prepare(ActorContext actor, UUID reportId, UUID versionId) throws IOException {
        var context = reviews.context(actor, reportId, false).orElseThrow(VerificationHandoffService::unavailable);
        var version = reviews.verification(reportId, versionId).orElseThrow(VerificationHandoffService::unavailable);
        var report = reports.find(actor, reportId);
        var decisions = reviews.decisions(reportId).stream()
                .filter(d -> d.fields().verificationVersionIds().contains(versionId)).toList();
        var fields = version.fields();
        var result = tasks.resultForVerification(actor, reportId, versionId);
        return prepareArchive(zip -> {
            var writer = new HandoffArchiveWriter(zip, json);
            var missing = new ArrayList<Missing>();
            writer.markdown("report/original.md", "ORIGINAL_REPORT", reportId, report.markdown());
            var engineering = frozenBaseline.write(writer, actor, context, report);
            writer.json("verification/version.json", "VERIFICATION_VERSION", versionId, version);
            writer.json("decisions.json", "DECISIONS_REFERENCING_VERSION", versionId, decisions);
            var comparison = writeEvidence(writer, actor, reportId, version, "", missing);
            var verificationEntries = writer.entries();
            UUID taskVersionId = null;
            if (result.isPresent()) {
                var linked = result.get();
                if (!versionId.equals(linked.verificationVersionId())) throw inconsistent();
                taskVersionId = linked.taskVersionId();
                var task = tasks.version(actor, reportId, taskVersionId);
                writeTask(writer, actor, context, taskVersionId, task, engineering, missing);
                writer.markdown("result-materials.md", "RESULT_FILES", versionId, TaskHandoffMarkdown.materials("", verificationEntries));
                writeResult(writer, actor, context, task, linked, version);
            }
            String overview = HandoffMarkdown.overview(report.title(), context, version, comparison, decisions, missing,
                    verificationEntries, engineering != null || result.isPresent()) + (engineering == null ? "" : engineering.overview());
            if (result.isPresent()) overview += "\n## 任务及外部执行\n\n[保存的任务与交付要求](task.md) · [该任务版本的执行结果和补丁](result.md)。\n";
            writer.markdown("README.md", "HANDOFF_GUIDE", versionId, overview);
            writer.json("manifest.json", "MANIFEST", versionId, new Manifest(result.isPresent() ? 2 : 1, Instant.now(),
                    context, versionId, version.version(), fields.baselineBuildId(), fields.candidateBuildId(),
                    List.copyOf(missing), writer.entries(), engineering, taskVersionId));
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TemporaryReportArchive prepareExecution(ActorContext actor, UUID reportId, UUID taskVersionId) throws IOException {
        var context = reviews.context(actor, reportId, false).orElseThrow(VerificationHandoffService::unavailable);
        var task = tasks.version(actor, reportId, taskVersionId);
        var report = reports.find(actor, reportId);
        return prepareArchive(zip -> {
            var writer = new HandoffArchiveWriter(zip, json);
            var missing = new ArrayList<Missing>();
            writer.markdown("report/original.md", "ORIGINAL_REPORT", reportId, report.markdown());
            var engineering = frozenBaseline.write(writer, actor, context, report);
            writeTask(writer, actor, context, taskVersionId, task, engineering, missing);
            writer.markdown("README.md", "EXECUTION_GUIDE", taskVersionId, "# 外部执行包\n\n请从 [任务说明](task.md) 开始。"
                    + "原始研究报告、选定依据、已知缺项和返回要求均按这次交出的任务版本组织。\n");
            writer.json("manifest.json", "MANIFEST", taskVersionId, new ExecutionManifest(1, "TASK_EXECUTION",
                    Instant.now(), context, task.taskId(), task.id(), task.version(), engineering, missing, writer.entries()));
        });
    }

    private void writeTask(HandoffArchiveWriter writer, ActorContext actor, Context context, UUID requestedId,
                           TaskVersion task, FrozenBaselineHandoff.Original engineering, List<Missing> missing)
            throws IOException {
        if (!requestedId.equals(task.id()) || !context.reportId().equals(task.reportId())
                || !context.runId().equals(task.runId()) || !context.systemId().equals(task.systemId())
                || context.systemRevision() != task.systemRevision()
                || !Objects.equals(task.engineeringBaselineId(), engineering == null ? null : engineering.id())
                || !Objects.equals(task.engineeringBaselineVersion(), engineering == null ? null : engineering.version())) {
            throw inconsistent();
        }
        writer.json("task/version.json", "TASK_VERSION", task.id(), task);
        writer.markdown("task/suggestion.md", "REPORT_EXCERPT", task.id(), task.fields().excerpt());
        var taskGaps = new ArrayList<Missing>();
        for (UUID id : task.fields().verificationVersionIds()) {
            var verified = reviews.verification(context.reportId(), id).orElseThrow(VerificationHandoffService::unavailable);
            if (!id.equals(verified.id())) throw inconsistent();
            String prefix = "basis/" + id + "/";
            writer.json(prefix + "version.json", "VERIFICATION_BASIS", id, verified);
            writeEvidence(writer, actor, context.reportId(), verified, prefix, taskGaps);
            // Include the selected version's own returned artifacts, without traversing its upstream task graph.
            var returned = tasks.resultForVerification(actor, context.reportId(), id);
            if (returned.isPresent()) writeResultFiles(writer, actor, context, id, returned.get(), prefix);
            writer.markdown(prefix + "materials.md", "VERIFICATION_ORIGINALS_INDEX", id,
                    TaskHandoffMarkdown.materials(prefix, writer.entries()));
        }
        for (UUID id : task.fields().uploadIds()) writeSupplement(writer, actor, id, "task/materials/", taskGaps);
        writer.markdown("task.md", "TASK_INSTRUCTIONS", task.id(),
                TaskHandoffMarkdown.task(task, context, engineering, taskGaps, writer.entries()));
        missing.addAll(taskGaps);
    }

    private void writeResult(HandoffArchiveWriter writer, ActorContext actor, Context context, TaskVersion task,
                             TaskResult result, Verification verification) throws IOException {
        UUID id = verification.id();
        String path = writeResultFiles(writer, actor, context, id, result, "");
        writer.markdown("result.md", "EXECUTION_RESULT_GUIDE", id, TaskHandoffMarkdown.result(task, result, verification, path));
    }

    private String writeResultFiles(HandoffArchiveWriter writer, ActorContext actor, Context context, UUID verificationId,
                                    TaskResult result, String prefix) throws IOException {
        if (!verificationId.equals(result.verificationVersionId())) throw inconsistent();
        writer.json(prefix + "execution/result.json", "EXECUTION_RESULT", verificationId, result);
        var patch = result.patch();
        if (patch == null) return null;
        String path = prefix + "execution/patch/" + HandoffArchiveWriter.leaf(patch.name(), "returned.patch");
        byte[] bytes = tasks.patch(actor, context.reportId(), verificationId);
        writer.original(path, "EXECUTION_PATCH", verificationId, patch.name(), patch.size(), patch.sha256(), new ByteArrayInputStream(bytes));
        return path;
    }

    private BuildEvidenceComparison writeEvidence(HandoffArchiveWriter writer, ActorContext actor, UUID reportId,
                                                   Verification version, String prefix, List<Missing> missing)
            throws IOException {
        var fields = version.fields();
        if ((fields.baselineBuildId() == null) != (fields.candidateBuildId() == null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "保存的版本缺少一侧构建引用，无法导出完整对照。");
        }
        BuildEvidenceComparison comparison = null;
        if (fields.baselineBuildId() == null) {
            missing.add(new Missing("构建对照", version.id().toString(), "此验证版本保存时未关联构建批次。"));
        } else {
            var baseline = builds.require(actor, reportId, fields.baselineBuildId());
            var candidate = builds.require(actor, reportId, fields.candidateBuildId());
            if (baseline.summary().role() != Role.BASELINE || candidate.summary().role() != Role.CANDIDATE) throw inconsistent();
            comparison = BuildEvidenceComparison.compare(baseline, candidate);
            writeBuild(writer, actor, reportId, baseline, prefix, missing);
            writeBuild(writer, actor, reportId, candidate, prefix, missing);
            writer.json(prefix + "comparison/diff.json", "TESTCASE_COMPARISON", version.id(), comparison);
            writer.markdown(prefix + "comparison/results.md", "COMPARISON_GUIDE", version.id(),
                    HandoffMarkdown.comparison(baseline, candidate, comparison));
        }
        for (UUID id : fields.uploadIds()) writeSupplement(writer, actor, id, prefix + "supplementary/", missing);
        return comparison;
    }

    private TemporaryReportArchive prepareArchive(TemporaryReportArchive.Contents contents) throws IOException {
        var archive = TemporaryReportArchive.prepare(contents);
        // A failed transaction completion can prevent the controller from ever receiving this handle.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        try { archive.close(); }
                        catch (IOException e) { throw new UncheckedIOException(e); }
                    }
                }
            });
        }
        return archive;
    }

    private void writeBuild(HandoffArchiveWriter writer, ActorContext actor, UUID reportId, Batch batch, String prefix, List<Missing> missing)
            throws IOException {
        var summary = batch.summary();
        String side = summary.role() == Role.BASELINE ? "baseline" : "candidate";
        String label = summary.role() == Role.BASELINE ? "基线材料" : "候选材料";
        String reference = summary.id().toString();
        var kinds = batch.files().stream().map(EvidenceFile::kind).toList();
        if (!kinds.contains(FileKind.MAVEN_LOG)) missing.add(new Missing(label, reference, "未导入 Maven 日志。"));
        if (!kinds.contains(FileKind.COMMAND_RECORD)) missing.add(new Missing(label, reference, "未导入命令记录。"));
        if (!batch.commandRecord().excludedClasses().isEmpty() && batch.excludedMethods().isEmpty()) {
            missing.add(new Missing(label, reference, "命令有排除类，具体排除方法数量缺少独立依据。"));
        }
        writer.json(prefix + "comparison/" + side + ".json", "PARSED_BUILD", summary.id(), new ParsedImport(summary,batch.metadata(),
                batch.commandRecord(),batch.log(),batch.excludedMethods(),batch.files(),batch.warnings(),batch.modules()));
        for (var file : batch.files()) {
            var original = builds.download(actor, reportId, summary.id(), file.id());
            if (!file.equals(original.file())) throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "原件与已保存构建清单不一致，交接包未生成。");
            writer.original(prefix + HandoffArchiveWriter.originalPath(summary.role(), file), file.kind().name(), file.id(),
                    file.name(), file.size(), file.sha256(), new ByteArrayInputStream(original.content()));
        }
    }

    private void writeSupplement(HandoffArchiveWriter writer, ActorContext actor, UUID uploadId, String directory, List<Missing> missing)
            throws IOException {
        // Match the owner-scoped visibility used by the verification form, including for workspace administrators.
        var file = uploads.findVisible(actor.workspaceId(),actor.userId(),false,uploadId).orElse(null);
        if (file == null || !"SUCCEEDED".equals(file.status())) {
            missing.add(new Missing("补充资料", uploadId.toString(), "当前账号没有可取得的已处理资料。"));
            return;
        }
        InputStream stored;
        try { stored = storage.open(uploadId + ".bin"); }
        catch (IOException e) {
            missing.add(new Missing("补充资料", uploadId.toString(), "归档文件当前无法读取。"));
            return;
        }
        String fallback = switch (file.mediaType()) {
            case "application/pdf" -> "attachment.pdf";
            case "text/markdown" -> "attachment.md";
            default -> "attachment.txt";
        };
        String path = directory + uploadId + "/" + HandoffArchiveWriter.leaf(file.originalName(), fallback);
        try (stored) {
            writer.original(path,"SUPPLEMENT",uploadId,file.originalName(),file.byteSize(),file.sha256(),stored);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "未能完整写入补充证据，交接包未生成。", e);
        }
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到当前账号可交接的报告及验证版本。");
    }

    private static ResponseStatusException inconsistent() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "已经保存的版本与关联依据不一致，交接包未生成。");
    }
}
