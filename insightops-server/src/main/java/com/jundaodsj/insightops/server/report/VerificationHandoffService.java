package com.jundaodsj.insightops.server.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.infrastructure.report.TemporaryReportArchive;
import com.jundaodsj.insightops.knowledge.application.KnowledgeFileStorage;
import com.jundaodsj.insightops.knowledge.application.KnowledgeUploadStore;
import com.jundaodsj.insightops.report.application.BuildEvidence.*;
import com.jundaodsj.insightops.report.application.BuildEvidenceComparison;
import com.jundaodsj.insightops.report.application.ReportReviewStore;
import com.jundaodsj.insightops.report.application.ReportReviewStore.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class VerificationHandoffService {
    private final ReportReviewStore reviews;
    private final ReportService reports;
    private final BuildEvidenceService builds;
    private final KnowledgeUploadStore uploads;
    private final KnowledgeFileStorage storage;
    private final ObjectMapper json;

    public VerificationHandoffService(ReportReviewStore reviews, ReportService reports, BuildEvidenceService builds,
                                      KnowledgeUploadStore uploads, KnowledgeFileStorage storage, ObjectMapper json) {
        this.reviews = reviews;
        this.reports = reports;
        this.builds = builds;
        this.uploads = uploads;
        this.storage = storage;
        this.json = json;
    }

    record Missing(String category, String reference, String reason) { }
    record Manifest(int formatVersion, Instant exportedAt, Context report, UUID verificationVersionId, int version,
                    UUID baselineBuildId, UUID candidateBuildId, List<Missing> missing,
                    List<HandoffArchiveWriter.Entry> entries) { }
    private record ParsedImport(Summary summary, Metadata userDeclarations, BuildRecord commandRecord, LogFacts log,
                                List<ExcludedMethod> excludedMethodsFromManifest, List<EvidenceFile> files, List<String> warnings) { }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TemporaryReportArchive prepare(ActorContext actor, UUID reportId, UUID versionId) throws IOException {
        var context = reviews.context(actor, reportId, false).orElseThrow(VerificationHandoffService::unavailable);
        var version = reviews.verification(reportId, versionId).orElseThrow(VerificationHandoffService::unavailable);
        var report = reports.find(actor, reportId);
        var decisions = reviews.decisions(reportId).stream()
                .filter(d -> d.fields().verificationVersionIds().contains(versionId)).toList();
        var fields = version.fields();
        if ((fields.baselineBuildId() == null) != (fields.candidateBuildId() == null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "保存的版本缺少一侧构建引用，无法导出完整对照。");
        }

        return TemporaryReportArchive.prepare(zip -> {
            var writer = new HandoffArchiveWriter(zip, json.writerWithDefaultPrettyPrinter());
            var missing = new ArrayList<Missing>();
            writer.markdown("report/original.md", "ORIGINAL_REPORT", reportId, report.markdown());
            writer.json("verification/version.json", "VERIFICATION_VERSION", versionId, version);
            writer.json("decisions.json", "DECISIONS_REFERENCING_VERSION", versionId, decisions);
            if (fields.baselineBuildId() == null) {
                missing.add(new Missing("构建对照", versionId.toString(), "此验证版本保存时未关联构建批次。"));
            } else {
                var baseline = builds.require(actor, reportId, fields.baselineBuildId());
                var candidate = builds.require(actor, reportId, fields.candidateBuildId());
                var comparison = BuildEvidenceComparison.compare(baseline, candidate);
                writeBuild(writer, actor, reportId, baseline, missing);
                writeBuild(writer, actor, reportId, candidate, missing);
                writer.json("comparison/diff.json", "TESTCASE_COMPARISON", versionId, comparison);
                writer.markdown("comparison/results.md", "COMPARISON_GUIDE", versionId,
                        HandoffMarkdown.comparison(baseline, candidate, comparison));
            }
            for (UUID uploadId : fields.uploadIds()) writeSupplement(writer, actor, uploadId, missing);
            writer.markdown("README.md", "HANDOFF_GUIDE", versionId,
                    HandoffMarkdown.overview(report.title(), context, version, decisions, missing, writer.entries()));
            writer.json("manifest.json", "MANIFEST", versionId, new Manifest(1,Instant.now(),context,versionId,
                    version.version(),fields.baselineBuildId(),fields.candidateBuildId(),List.copyOf(missing),writer.entries()));
        });
    }

    private void writeBuild(HandoffArchiveWriter writer, ActorContext actor, UUID reportId, Batch batch, List<Missing> missing)
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
        writer.json("comparison/" + side + ".json", "PARSED_BUILD", summary.id(), new ParsedImport(summary,batch.metadata(),
                batch.commandRecord(),batch.log(),batch.excludedMethods(),batch.files(),batch.warnings()));
        for (var file : batch.files()) {
            var original = builds.download(actor, reportId, summary.id(), file.id());
            if (!file.equals(original.file())) throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "原件与已保存构建清单不一致，交接包未生成。");
            writer.original(HandoffArchiveWriter.originalPath(summary.role(), file), file.kind().name(), file.id(),
                    file.name(), file.size(), file.sha256(), new ByteArrayInputStream(original.content()));
        }
    }

    private void writeSupplement(HandoffArchiveWriter writer, ActorContext actor, UUID uploadId, List<Missing> missing)
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
        String path = "supplementary/" + uploadId + "/" + HandoffArchiveWriter.leaf(file.originalName(), fallback);
        try (stored) {
            writer.original(path,"SUPPLEMENT",uploadId,file.originalName(),file.byteSize(),file.sha256(),stored);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "未能完整写入补充证据，交接包未生成。", e);
        }
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到当前账号可交接的报告及验证版本。");
    }
}
