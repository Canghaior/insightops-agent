package com.jundaodsj.insightops.server.localexecution;

import com.jundaodsj.insightops.report.application.BuildEvidence.Batch;
import com.jundaodsj.insightops.report.application.BuildEvidence.Status;
import com.jundaodsj.insightops.report.application.BuildEvidenceComparison;
import com.jundaodsj.insightops.report.application.BuildEvidenceComparison.Group;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** A separate observation of what the new tests do on the old implementation. */
record LocalRegressionComparison(int baselineExecuted, int baselinePassingOnCandidate, int failedThenPassed,
                                 int undistinguished, List<Case> cases, List<String> testFiles,
                                 List<String> unobservedTestSources, String limitation) {
    record Case(String suite, String className, String name, String outcome, String reason) {}
    private record Identity(String suite, String className, String name) {
        Identity(Group row) { this(row.suite(), row.className(), row.name()); }
    }

    static LocalRegressionComparison compare(LocalBuildEvidence.Observed baseline, LocalBuildEvidence.Observed replay,
            LocalBuildEvidence.Observed candidate, List<String> files, String issue) {
        if (baseline.parsed() == null || candidate.parsed() == null) {
            return new LocalRegressionComparison(baseline.ran(), 0, 0, 0, List.of(), files, List.of(),
                    "基线或候选缺少可比较的 Surefire XML。" + issue);
        }
        Batch probe = null;
        if (issue.isEmpty() && replay != null && replay.parsed() != null) {
            if (replay.build().imageId().equals(candidate.build().imageId())
                    && replay.build().imageId().equals(baseline.build().imageId())) probe = replay.parsed();
            else issue = "构建镜像发生变化，无法区分新增测试的结果。";
        }
        return compare(baseline.parsed(), probe, candidate.parsed(), files, issue);
    }

    static LocalRegressionComparison compare(Batch baseline, Batch probe, Batch candidate, List<String> files, String issue) {
        List<Group> original = BuildEvidenceComparison.compare(baseline, candidate).groups();
        var existing = original.stream().filter(group -> !group.baseline().isEmpty())
                .map(Identity::new).collect(Collectors.toSet());
        int ran = baseline.summary().counts().total() - baseline.summary().counts().skipped();
        int kept = 0;
        for (Group group : original) {
            int count = group.baselineCounts().total() - group.baselineCounts().skipped();
            // For repeated identities only count original occurrences when every candidate occurrence passed.
            if (group.candidateCounts().passed() == group.candidateCounts().total()) {
                kept += Math.min(count, group.candidateCounts().passed());
            }
        }
        var sources = files.stream().filter(p -> p.startsWith("src/test/java/") && p.endsWith(".java")).toList();
        Set<String> introduced = sources.stream().map(LocalRegressionComparison::classFromPath).collect(Collectors.toSet());
        List<Group> comparisons = probe == null ? original : BuildEvidenceComparison.compare(probe, candidate).groups();
        var located = comparisons.stream().map(g -> g.className().split("\\$", 2)[0]).collect(Collectors.toSet());
        var unobserved = sources.stream().filter(path -> !located.contains(classFromPath(path))).toList();
        List<Case> rows = new ArrayList<>();
        int distinguished = 0;
        for (Group group : comparisons) {
            if (existing.contains(new Identity(group))) continue;
            String type = group.className().split("\\$", 2)[0];
            String reason;
            if (!introduced.contains(type)) reason = "未能归属到新增测试源文件。";
            else if (probe == null) reason = "没有可比较的原实现对照。";
            else if (group.baseline().size() != 1 || group.candidate().size() != 1) reason = "测试身份缺失或重复，无法逐项配对。";
            else if (group.baseline().getFirst().status() != Status.FAILURE) reason = "原实现未记录该项断言失败。";
            else if (group.candidate().getFirst().status() != Status.PASSED) reason = "候选未记录该项通过。";
            else reason = "";
            if (reason.isEmpty()) distinguished++;
            rows.add(new Case(group.suite(), group.className(), group.name(),
                    reason.isEmpty() ? "FAILURE_TO_PASS" : "UNCONFIRMED", reason));
        }
        if (distinguished == 0 && issue.isEmpty()) issue = "尚无可逐项配对的新增用例证明旧实现失败、候选通过。";
        return new LocalRegressionComparison(ran, kept, distinguished, rows.size() - distinguished,
                List.copyOf(rows), List.copyOf(files), unobserved, issue);
    }

    private static String classFromPath(String path) {
        return path.substring("src/test/java/".length(), path.length() - ".java".length()).replace('/', '.');
    }

    String summary() {
        return "基线实际执行 " + baselineExecuted + " 项，候选可确认原回归通过 " + baselinePassingOnCandidate
                + " 项；新增用例观察到旧实现断言失败、候选通过 " + failedThenPassed + " 项，另有 "
                + undistinguished + " 组无法区分。" + (unobservedTestSources.isEmpty() ? "" :
                "另有 " + unobservedTestSources.size() + " 个新增 Java 测试目录源文件未在 XML 定位到。") + limitation;
    }
}
