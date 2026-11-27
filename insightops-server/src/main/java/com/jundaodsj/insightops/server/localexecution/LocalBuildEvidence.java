package com.jundaodsj.insightops.server.localexecution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.report.application.BuildEvidence;
import com.jundaodsj.insightops.report.application.BuildEvidence.Batch;
import com.jundaodsj.insightops.report.application.BuildEvidence.Counts;
import com.jundaodsj.insightops.report.application.BuildEvidence.Role;
import com.jundaodsj.insightops.report.application.BuildEvidenceComparison;
import com.jundaodsj.insightops.report.application.ReportReviewStore.Execution;
import com.jundaodsj.insightops.report.application.ReportReviewStore.Impact;
import com.jundaodsj.insightops.server.report.BuildEvidenceService;
import com.jundaodsj.insightops.server.report.EngineeringTaskService;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class LocalBuildEvidence {
    static final String EXECUTOR = "InsightOps 本地执行器";

    record Observed(LocalMavenBuild.Build build, Batch parsed, String issue, String declaration,
                    Map<String, List<MultipartFile>> parts) {
        Counts counts() { return parsed == null ? null : parsed.summary().counts(); }
        int ran() { return counts() == null ? 0 : counts().total() - counts().skipped(); }
        boolean passed() {
            return build.execution().ok() && issue.isEmpty() && ran() > 0 &&
                    counts().failures() == 0 && counts().errors() == 0;
        }

        Map<String, Object> observation() {
            var facts = new LinkedHashMap<String, Object>();
            facts.put("build", build.label());
            facts.put("startedAt", build.execution().startedAt());
            facts.put("endedAt", build.execution().endedAt());
            facts.put("exitCode", build.execution().exitCode());
            facts.put("end", build.execution().end());
            facts.put("passed", passed());
            facts.put("counts", counts());
            facts.put("sourcePatchSha256", build.patchSha256());
            facts.put("command", build.command());
            facts.put("issue", issue);
            String log = build.execution().text();
            facts.put("logTail", log.substring(Math.max(0, log.length() - 4500)).replace("\u0000", ""));
            return facts;
        }
    }

    private final BuildEvidenceService builds;
    private final EngineeringTaskService tasks;
    private final ObjectMapper json;

    LocalBuildEvidence(BuildEvidenceService builds, EngineeringTaskService tasks, ObjectMapper json) {
        this.builds = builds;
        this.tasks = tasks;
        this.json = json;
    }

    Observed inspect(ActorContext actor, UUID report, LocalMavenBuild.Build build, Role role, String revision) throws IOException {
        if (!build.collectionNote().isEmpty()) return unavailable(build, build.collectionNote());
        if (build.xml().isEmpty()) return unavailable(build, "本次未取得可解析的 Surefire XML。");
        var parts = new HashMap<String, List<MultipartFile>>();
        parts.put("xmlFiles", build.xml().stream().<MultipartFile>map(Original::new).toList());
        if (Files.size(build.artifacts().resolve("maven.log")) > 0) {
            parts.put("mavenLog", List.of(new Original(build.artifacts().resolve("maven.log"))));
        }
        parts.put("commandRecord", List.of(new Original(build.artifacts().resolve("command.json"))));
        var declaration = json.writeValueAsString(new BuildEvidenceService.ImportCommand(UUID.randomUUID(), role,
                "本地执行 · " + build.label(), "隔离 Maven 容器 " + build.imageId() + " · 网络关闭",
                build.execution().startedAt().toString(), EXECUTOR, build.execution().exitCode(),
                "Git 基线 " + revision + (build.patchSha256().isEmpty() ? "" : " · 补丁 SHA-256 " + build.patchSha256())));
        try {
            var parsed = builds.inspectBatch(actor, report, declaration, parts);
            return new Observed(build, parsed, "", declaration, Map.copyOf(parts));
        } catch (IllegalArgumentException e) {
            return unavailable(build, "产品未能解析本次原件。" + e.getMessage());
        }
    }

    private static Observed unavailable(LocalMavenBuild.Build build, String reason) {
        return new Observed(build, null, reason, "", Map.of());
    }

    static boolean preservesBaseline(Observed baseline, Observed candidate) {
        if (candidate.parsed() == null || baseline.parsed() == null) return false;
        return BuildEvidenceComparison.compare(baseline.parsed(), candidate.parsed()).groups().stream().allMatch(group ->
                group.candidateCounts().total() - group.candidateCounts().skipped()
                        >= group.baselineCounts().total() - group.baselineCounts().skipped());
    }

    UUID record(ActorContext actor, LocalRunStore.Run run, Observed baseline, Observed candidate, byte[] patch,
                boolean passed, boolean detectedChange, String note, String model) {
        // The existing verification pairs builds. Unparsed attempts are still in the run archive.
        boolean paired = baseline.parsed() != null && candidate.parsed() != null;
        UUID baselineId = paired ? importBatch(actor, run.reportId(), baseline) : null;
        UUID candidateId = paired ? importBatch(actor, run.reportId(), candidate) : null;
        String patchHash = LocalWorkspaces.sha(patch);
        var fields = new EngineeringTaskService.ResultCommand(run.id(), null, Execution.EXECUTED,
                passed && detectedChange ? Impact.SUPPORTS : Impact.INCONCLUSIVE,
                "InsightOps 从冻结的 Git 提交创建独立副本，使用 " + model + " 调用工具试改。基线和候选在相同隔离环境运行 Maven test。",
                "本机 Maven 容器 " + candidate.build().imageId() + "，独立源码快照，网络关闭。",
                note + " 运行 ID " + run.id() + "，基线 " + describe(baseline) + "，候选 " + describe(candidate) + "。",
                "只覆盖该仓库由 Maven test 产生的结果，未包含部署、性能和外部服务验收。工程执行记录可从本地运行下载。",
                "", EXECUTOR, candidate.build().execution().endedAt(), run.input().sourceRevision(),
                "工作副本补丁 SHA-256 " + patchHash, passed ? "" : "核对候选日志和未通过条件。", List.of(),
                baselineId, candidateId, false);
        var file = new EngineeringTaskService.PatchFile("insightops-local-change.patch", patch);
        return tasks.record(actor, run.reportId(), run.taskVersionId(), fields, file).verificationVersionId();
    }

    private UUID importBatch(ActorContext actor, UUID report, Observed ready) {
        return builds.importBatch(actor, report, ready.declaration(), ready.parts()).summary().id();
    }

    private static String describe(Observed build) {
        var counts = build.counts();
        return "退出码 " + (build.build().execution().exitCode() == null ? "未取得" : build.build().execution().exitCode()) +
                (counts == null ? "，暂无解析计数" : "，" + counts.passed() + " 通过、" + counts.failures() + " failure、" +
                        counts.errors() + " error、" + counts.skipped() + " skipped");
    }

    private record Original(Path path) implements MultipartFile {
        @Override public String getName() { return path.getFileName().toString(); }
        @Override public String getOriginalFilename() { return getName(); }
        @Override public String getContentType() { return "application/octet-stream"; }
        @Override public boolean isEmpty() { return getSize() == 0; }
        @Override public long getSize() {
            try { return Files.size(path); }
            catch (IOException e) { throw new IllegalStateException("无法读取已有构建原件。", e); }
        }
        @Override public byte[] getBytes() throws IOException { return Files.readAllBytes(path); }
        @Override public InputStream getInputStream() throws IOException { return Files.newInputStream(path); }
        @Override public void transferTo(File destination) throws IOException { Files.copy(path, destination.toPath()); }
    }
}
