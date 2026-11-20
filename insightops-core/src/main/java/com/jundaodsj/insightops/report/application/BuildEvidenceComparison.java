package com.jundaodsj.insightops.report.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import com.jundaodsj.insightops.report.application.BuildEvidence.Batch;
import com.jundaodsj.insightops.report.application.BuildEvidence.Role;
import com.jundaodsj.insightops.report.application.BuildEvidence.Status;
import com.jundaodsj.insightops.report.application.BuildEvidence.TestCase;

/** Compares observations in two saved single-build imports. XML ordinals are local to each file. */
public record BuildEvidenceComparison(UUID baselineId, UUID candidateId, Scope scope, Counts baselineCounts,
                                      Counts candidateCounts, List<Group> groups, String objectiveSummary,
                                      List<String> limitations) {
    public enum Scope { SAME_OBSERVED, DIFFERENT_OBSERVED }
    public enum Change { UNCHANGED, CHANGED, AMBIGUOUS, BASELINE_ONLY, CANDIDATE_ONLY }

    public record Occurrence(UUID fileId, int ordinal, Status status, String type, String message, String stack) {
        static Occurrence from(TestCase item) {
            return new Occurrence(item.fileId(), item.ordinal(), item.status(), item.type(), item.message(), item.stack());
        }
    }

    public record Counts(int passed, int failure, int error, int skipped, int total) {
        private static Counts of(List<Occurrence> items) {
            int pass = 0, fail = 0, error = 0, skip = 0;
            for (var item : items) {
                switch (item.status()) {
                    case PASSED -> pass++;
                    case FAILURE -> fail++;
                    case ERROR -> error++;
                    case SKIPPED -> skip++;
                }
            }
            return new Counts(pass, fail, error, skip, items.size());
        }
        private static Counts of(BuildEvidence.Counts c) {
            return new Counts(c.passed(), c.failures(), c.errors(), c.skipped(), c.total());
        }
        public String describe() {
            return "%d 项，%d 通过、%d 断言失败、%d 执行报错、%d 跳过".formatted(total, passed, failure, error, skipped);
        }
    }

    public record Group(String suite, String className, String name, Change change, Counts baselineCounts,
                        Counts candidateCounts, List<Occurrence> baseline, List<Occurrence> candidate) { }

    private record Identity(String suite, String className, String name) { }
    private static final Comparator<Identity> ID_ORDER = Comparator.comparing(Identity::suite)
            .thenComparing(Identity::className).thenComparing(Identity::name);

    public static BuildEvidenceComparison compare(Batch baseline, Batch candidate) {
        if (baseline.summary().id().equals(candidate.summary().id())
                || !baseline.summary().systemId().equals(candidate.summary().systemId())
                || baseline.summary().role() != Role.BASELINE || candidate.summary().role() != Role.CANDIDATE) {
            throw new IllegalArgumentException("请选择同一业务系统中的一份基线构建和一份候选构建。");
        }
        var left = index(baseline.cases());
        var right = index(candidate.cases());
        var keys = new TreeSet<>(ID_ORDER);
        keys.addAll(left.keySet());
        keys.addAll(right.keySet());
        var groups = new ArrayList<Group>();
        var scope = Scope.SAME_OBSERVED;
        int ambiguous = 0, changed = 0;
        for (var key : keys) {
            var before = left.getOrDefault(key, List.of());
            var after = right.getOrDefault(key, List.of());
            if (before.size() != after.size()) scope = Scope.DIFFERENT_OBSERVED;
            var change = classify(before, after);
            if (change == Change.AMBIGUOUS) ambiguous++;
            if (change == Change.CHANGED) changed++;
            groups.add(new Group(key.suite(), key.className(), key.name(), change, Counts.of(before), Counts.of(after),
                    List.copyOf(before), List.copyOf(after)));
        }

        var leftCounts = Counts.of(baseline.summary().counts());
        var rightCounts = Counts.of(candidate.summary().counts());
        String observation = scope == Scope.SAME_OBSERVED ? "两边 XML 中的测试身份和出现次数一致。"
                : "两边 XML 中的测试身份或出现次数不同，无法按同一范围比较通过率。";
        String summary = "基线 XML 记录 " + leftCounts.describe() + "。候选 XML 记录 " + rightCounts.describe() + "。" + observation
                + "可逐项匹配的身份中，" + changed + " 项状态发生变化。";
        if (ambiguous > 0) summary += "另有 " + ambiguous + " 组重复身份，仅能比较各侧次数和状态分布。";
        var limits = new LinkedHashSet<String>();
        limits.add("这些数量仅覆盖已导入的 XML。未出现的方法执行情况未知，获知构建是否成功还需核对日志与退出码。");
        if (leftCounts.total() == 0 && rightCounts.total() == 0) limits.add("两份 XML 均未记录 testcase，无法比较业务回归表现。");
        if (!Objects.equals(baseline.commandRecord().selector(), candidate.commandRecord().selector())) {
            limits.add("两边记录的命令选择器不一致或有一侧缺失。请核对各自执行范围。");
        }
        baseline.warnings().forEach(s -> limits.add("基线：" + s));
        candidate.warnings().forEach(s -> limits.add("候选：" + s));
        return new BuildEvidenceComparison(baseline.summary().id(), candidate.summary().id(), scope, leftCounts,
                rightCounts, List.copyOf(groups), summary, List.copyOf(limits));
    }

    private static TreeMap<Identity,List<Occurrence>> index(List<TestCase> cases) {
        var buckets = new TreeMap<Identity,List<Occurrence>>(ID_ORDER);
        for (var item : cases) {
            var key = new Identity(item.suite(), item.className(), item.name());
            buckets.computeIfAbsent(key, ignored -> new ArrayList<>()).add(Occurrence.from(item));
        }
        // File identities and ordinals point into the originals; neither is an invocation key across builds.
        buckets.values().forEach(items -> items.sort(Comparator.comparing(Occurrence::fileId)
                .thenComparingInt(Occurrence::ordinal)));
        return buckets;
    }

    private static Change classify(List<Occurrence> before, List<Occurrence> after) {
        if (before.isEmpty()) return Change.CANDIDATE_ONLY;
        if (after.isEmpty()) return Change.BASELINE_ONLY;
        if (before.size() != 1 || after.size() != 1) return Change.AMBIGUOUS;
        return before.getFirst().status() == after.getFirst().status() ? Change.UNCHANGED : Change.CHANGED;
    }
}
