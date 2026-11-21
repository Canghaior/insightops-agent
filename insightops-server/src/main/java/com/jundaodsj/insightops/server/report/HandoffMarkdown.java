package com.jundaodsj.insightops.server.report;

import com.jundaodsj.insightops.report.application.BuildEvidence.*;
import com.jundaodsj.insightops.report.application.BuildEvidenceComparison;
import com.jundaodsj.insightops.report.application.ReportReviewStore.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class HandoffMarkdown {
    private HandoffMarkdown() { }

    static String overview(String title, Context context, Verification verification, BuildEvidenceComparison comparison, List<Decision> decisions,
                           List<VerificationHandoffService.Missing> missing, List<HandoffArchiveWriter.Entry> entries,
                           boolean additionalEvidence) {
        var f = verification.fields();
        var text = new StringBuilder("# 验证交接包\n\n");
        block(text, title);
        text.append("本包固定到档案修订 ").append(context.systemRevision())
                .append("、验证第 ").append(verification.version()).append(" 版。")
                .append("原研究正文见 [原报告](report/original.md)，记录原文见 [指定验证版本](verification/version.json)。\n\n")
                .append("- 报告 ").append(context.reportId()).append("\n- 源运行 ").append(context.runId())
                .append("\n- 业务系统 ").append(context.systemId()).append("\n- 验证版本 ").append(verification.id()).append('\n');
        if (verification.previousVersionId() != null) text.append("- 更正自 ").append(verification.previousVersionId()).append('\n');
        text.append("\n## 验证的问题与来源\n\n");
        field(text, "待验证判断", f.question());
        field(text, "原建议摘录或位置", f.suggestionRef());
        field(text, "验证建议来自", f.origin() == Origin.MODEL ? "原模型建议" : "人工或外部工具补充");
        field(text, "实际执行者（记录者填写）", f.executedBy());
        field(text, "验证记录者与保存时间", verification.author().name() + " · " + verification.createdAt());
        text.append("## 工程原件与平台解析\n\n");
        if (comparison != null) {
            text.append("打开 [两版结果与具体差异](comparison/results.md) 查看来源、范围、错误和原件入口。")
                    .append("完整逐项对照见 [对照数据](comparison/diff.json)。InsightOps 导入、解析并对照外部构建留下的文件。原测试时间见各批次的执行记录。\n\n");
            var issues = comparison.groups().stream()
                    .filter(group -> group.candidateCounts().failure() > 0 || group.candidateCounts().error() > 0).toList();
            if (!issues.isEmpty()) {
                text.append("候选 XML 中有 ").append(issues.size()).append(" 个测试身份包含断言失败或执行报错。\n\n");
                for (var issue : issues.stream().limit(20).toList()) {
                    block(text, issue.className() + "#" + issue.name() + " · 断言失败 " + issue.candidateCounts().failure()
                            + " 次、执行报错 " + issue.candidateCounts().error() + " 次");
                }
                if (issues.size() > 20) text.append("此处列出前 20 个身份，详表收录全部。\n\n");
            }
        }
        field(text, "保存此版本时的客观对照摘要", verification.buildSummary());
        text.append("## 记录者的观察与判断\n\n");
        field(text, "执行状态", switch (f.status()) {
            case NOT_EXECUTED -> "未执行"; case EXECUTED -> "已执行"; case BLOCKED -> "受阻";
        });
        field(text, "方法", f.method());
        field(text, "环境", f.environment());
        if (f.status() == Execution.BLOCKED) field(text, "阻塞原因", f.blocker());
        if (f.status() == Execution.EXECUTED) field(text, "实际观察", f.observation());
        field(text, "对待验证判断的影响", switch (f.impact()) {
            case SUPPORTS -> "支持该判断"; case CONTRADICTS -> "出现反证"; case INCONCLUSIVE -> "仍无法判断";
        });
        field(text, "适用范围、限制及尚未确认事项", f.limits());
        text.append("## 补充证据\n\n");
        var supplements = entries.stream().filter(e -> e.kind().equals("SUPPLEMENT")).toList();
        if (supplements.isEmpty()) text.append("本版本没有可随包提供的补充资料。").append('\n');
        else text.append("打开下列资料可核对独立的观察与记录。请结合各项实际覆盖的业务路径解读。\n\n");
        for (var file : supplements) {
            String localName = file.path().substring(file.path().lastIndexOf('/') + 1);
            text.append("- [`").append(localName).append("`](<").append(file.path()).append(">)");
            text.append(" · ").append(file.sourceId()).append("（").append(file.size()).append(" 字节）\n");
            if (!localName.equals(file.originalName())) field(text, "关联时的文件名", file.originalName());
        }
        text.append("\n## 已有人工决定\n\n");
        if (decisions.isEmpty()) text.append("截至导出时，没有已保存的人工决定引用此验证版本。\n\n");
        else {
            text.append("下列决定明确引用此版本。每项决定的其他依据也按已保存的版本 ID 列出。");
            text.append(additionalEvidence ? "本节列出的构建与补充资料按选定的验证版本取件。\n\n" : "包内原件仅来自选定的验证版本。\n\n");
            for (var decision : decisions) {
                field(text, "决定及决定者", decision.fields().choice() + " · " + decision.author().name() + " · " + decision.createdAt());
                field(text, "理由", decision.fields().reason());
                field(text, "具体依据版本", join(decision.fields().verificationVersionIds()));
                field(text, "重新考虑的条件", decision.fields().reconsiderWhen());
            }
        }
        text.append("## 缺项与核对方法\n\n");
        if (missing.isEmpty()) text.append("本次已关联并预期导出的原件均已写入。测试覆盖程度取决于实际提供的材料。\n\n");
        for (var gap : missing) field(text, gap.category(), gap.reference() + " · " + gap.reason());
        text.append("[文件清单](manifest.json) 登记其他文件的路径、字节数、SHA-256 和来源标识。")
                .append("对文件字节计算 SHA-256 即可核对内容。构建标签与 Git 提交的关系仍需构建方证明。\n");
        return text.toString();
    }

    static String comparison(Batch baseline, Batch candidate, BuildEvidenceComparison diff) {
        var text = new StringBuilder("# 构建结果对照\n\n");
        block(text, diff.objectiveSummary());
        writeBuild(text, "基线", baseline);
        writeBuild(text, "候选", candidate);
        text.append("## 状态变化、单侧出现、重复身份和原始错误\n\n");
        Map<UUID,String> paths = new HashMap<>();
        for (var batch : List.of(baseline,candidate)) {
            for (var file : batch.files()) paths.put(file.id(), HandoffArchiveWriter.originalPath(batch.summary().role(),file));
        }
        int listed = 0;
        for (var group : diff.groups()) {
            if (group.change() == BuildEvidenceComparison.Change.UNCHANGED
                    && group.baselineCounts().failure() == 0 && group.baselineCounts().error() == 0) continue;
            listed++;
            String change = switch (group.change()) {
                case UNCHANGED -> "两边状态相同"; case CHANGED -> "状态变化"; case AMBIGUOUS -> "重复身份，逐次对应关系未知";
                case BASELINE_ONLY -> "仅见于基线"; case CANDIDATE_ONLY -> "仅见于候选";
            };
            text.append("### ").append(listed).append(" · ").append(change).append("\n\n");
            block(text, group.suite() + "  /  " + group.className() + "#" + group.name());
            writeOccurrences(text, "基线", group.baseline(), paths);
            writeOccurrences(text, "候选", group.candidate(), paths);
        }
        if (listed == 0) text.append("已导入记录中没有上述差异或错误。\n\n");
        text.append("完整身份、各侧发生次数及异常类型、消息、堆栈见 [逐项数据](diff.json)。\n\n## 适用范围\n\n");
        for (String limit : diff.limitations()) block(text, limit);
        return text.toString();
    }

    private static void writeBuild(StringBuilder text, String label, Batch batch) {
        var m = batch.metadata();
        var record = batch.commandRecord();
        text.append("## ").append(label).append("构建\n\n");
        field(text, "批次与导入时间（平台记录）", batch.summary().id() + " · " + batch.summary().importedAt());
        field(text, "标签或提交号（用户填写）", m.label());
        field(text, "环境（用户填写）", m.environment());
        field(text, "执行者（用户填写）", m.executedBy());
        field(text, "测试执行时间（用户填写）", m.executedAt());
        field(text, "来源说明（用户填写）", m.sourceNote());
        field(text, "退出码（用户填写）", m.exitCode());
        field(text, "命令记录中的开始 / 结束时间", value(record.startedAt()) + " / " + value(record.endedAt()));
        field(text, "退出码（命令记录）", record.exitCode());
        field(text, "日志中的最终构建结果", batch.log().outcome());
        field(text, "命令记录中的执行参数（每行一个）", String.join("\n", record.command()));
        field(text, "命令选择范围", record.selector());
        field(text, "命令明确排除的类", join(record.excludedClasses()));
        if (batch.excludedMethods().isEmpty()) field(text, "具体排除方法数量", "未知，缺少可对应到命令排除类的源码或清单依据。");
        else {
            text.append("独立清单提供了 ").append(batch.excludedMethods().size()).append(" 个具体排除方法。")
                    .append("这些方法单列在 XML 计数之外。\n\n");
            for (var item : batch.excludedMethods()) block(text, item.className() + "#" + item.methodName() + " · " + item.source());
        }
    }

    private static void writeOccurrences(StringBuilder text, String side, List<BuildEvidenceComparison.Occurrence> rows, Map<UUID,String> paths) {
        if (rows.isEmpty()) text.append("- ").append(side).append("的 XML 没有此身份。\n");
        for (var row : rows) {
            text.append("- ").append(side).append(" · ").append(switch (row.status()) {
                case PASSED -> "通过"; case FAILURE -> "断言失败"; case ERROR -> "执行报错"; case SKIPPED -> "跳过";
            }).append(" · [原始 XML](<../").append(paths.get(row.fileId())).append(">) · testcase ").append(row.ordinal()).append('\n');
            if (row.status() == Status.ERROR || row.status() == Status.FAILURE) {
                text.append('\n');
                field(text, "异常类型", row.type());
                if (row.message() != null && !row.message().isBlank()) {
                    String message = row.message();
                    int end = message.offsetByCodePoints(0, Math.min(400, message.codePointCount(0, message.length())));
                    field(text, "异常消息", end == message.length() ? message : message.substring(0, end) + "…（全文见原始 XML）");
                }
            }
        }
        text.append('\n');
    }

    private static String join(List<?> values) {
        return String.join("、", values.stream().map(Object::toString).toList());
    }

    private static String value(Object value) {
        return value == null || value.toString().isBlank() ? "未知" : value.toString();
    }

    static void field(StringBuilder text, String label, Object content) {
        text.append(label).append("\n\n");
        block(text, value(content));
    }

    private static void block(StringBuilder text, String content) {
        // User labels, display names and log strings are literal data in Markdown.
        content.lines().forEach(line -> text.append("    ").append(line).append('\n'));
        text.append('\n');
    }
}
