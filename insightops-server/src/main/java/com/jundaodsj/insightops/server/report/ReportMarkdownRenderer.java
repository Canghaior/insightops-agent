package com.jundaodsj.insightops.server.report;

import com.jundaodsj.insightops.report.application.ReportDeliveryStore;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

final class ReportMarkdownRenderer {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");

    String render(ReportDeliveryStore.ReportQuery query,
                  List<ReportDeliveryStore.ReportItem> items) {
        long high = items.stream().filter(item -> "HIGH".equals(item.riskLevel())).count();
        StringBuilder output = new StringBuilder();
        output.append("# ").append(plain(query.title())).append("\n\n")
                .append("- 报告周期：").append(DATE_TIME.format(query.periodStart().atZone(ZONE)))
                .append(" 至 ").append(DATE_TIME.format(query.periodEnd().atZone(ZONE))).append('\n')
                .append("- 情报数量：").append(items.size()).append('\n')
                .append("- 高风险数量：").append(high).append('\n')
                .append("- 生成方式：InsightOps 已完成情报分析快照\n\n")
                .append("## 执行摘要\n\n")
                .append("本报告汇总 ").append(items.size()).append(" 条有官方证据的技术情报，其中 ")
                .append(high).append(" 条为高风险。报告内容是生成时的不可变快照。\n\n");
        int index = 1;
        for (var item : items) {
            output.append("## ").append(index++).append(". ").append(plain(item.projectName()));
            if (item.versionTag() != null && !item.versionTag().isBlank()) {
                output.append(' ').append(plain(item.versionTag()));
            }
            output.append("\n\n")
                    .append("- 类型：").append(plain(item.eventType())).append('\n')
                    .append("- 风险：").append(plain(item.riskLevel())).append('\n')
                    .append("- 建议：").append(plain(item.recommendation())).append('\n')
                    .append("- 证据状态：").append(plain(item.evidenceStatus())).append('\n')
                    .append("- 发生时间：").append(DATE_TIME.format(item.occurredAt().atZone(ZONE))).append("\n\n")
                    .append("### 结论\n\n").append(plain(item.oneLineSummary())).append("\n\n");
            section(output, "主要变化", item.majorChanges());
            paragraph(output, "Java 影响", item.javaImpact());
            paragraph(output, "升级价值", item.upgradeValue());
            section(output, "风险", item.risks());
            section(output, "建议行动", item.recommendedActions());
            output.append("### 官方证据\n\n");
            List<String> evidence = item.evidenceUrls().isEmpty()
                    ? List.of(item.sourceUrl()) : item.evidenceUrls();
            for (String url : evidence) {
                if (url != null && !url.isBlank()) output.append("- <").append(url.trim()).append(">\n");
            }
            output.append('\n');
        }
        output.append("---\n\n由 InsightOps Agent 生成。关键结论请通过官方证据链接复核。\n");
        return output.toString();
    }

    private static void section(StringBuilder output, String title, List<String> values) {
        if (values == null || values.isEmpty()) return;
        output.append("### ").append(title).append("\n\n");
        for (String value : values) output.append("- ").append(plain(value)).append('\n');
        output.append('\n');
    }

    private static void paragraph(StringBuilder output, String title, String value) {
        if (value == null || value.isBlank()) return;
        output.append("### ").append(title).append("\n\n").append(plain(value)).append("\n\n");
    }

    private static String plain(String value) {
        return value == null ? "未提供" : value.replace("\u0000", "").replace("\r", " ").trim();
    }
}
