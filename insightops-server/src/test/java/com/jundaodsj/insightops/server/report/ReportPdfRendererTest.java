package com.jundaodsj.insightops.server.report;

import com.jundaodsj.insightops.report.application.ReportDeliveryStore;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ReportPdfRendererTest {
    private static final Instant NOW = Instant.parse("2026-08-20T02:00:00Z");

    @Test
    void rendersReadableMultipageChineseReport() throws Exception {
        List<ReportDeliveryStore.ReportItem> items = IntStream.rangeClosed(1, 12)
                .mapToObj(ReportPdfRendererTest::item).toList();
        var report = new ReportDeliveryStore.ReportRecord(
                UUID.randomUUID(), "Spring AI 与 Java Agent 技术情报周报", "CUSTOM",
                NOW.minus(Duration.ofDays(7)), NOW, List.of(items.getFirst().projectId()),
                List.of("GITHUB_RELEASE"), items.size(), 4, items,
                "# Spring AI 与 Java Agent 技术情报周报", NOW);

        String fontPath = System.getProperty("insightops.pdf.qa-font", "");
        if (fontPath.isBlank()) {
            Path localNoto = Path.of("C:/Windows/Fonts/NotoSansSC-VF.ttf");
            if (Files.exists(localNoto)) fontPath = localNoto.toString();
        }
        byte[] pdf = new ReportPdfRenderer(fontPath).render(report);

        assertThat(pdf).startsWith("%PDF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertThat(pdf.length).isGreaterThan(10_000);
        try (PdfReader reader = new PdfReader(pdf)) {
            assertThat(reader.getNumberOfPages()).isGreaterThanOrEqualTo(3);
            assertThat(reader.getInfo().get("Title")).isEqualTo(report.title());
        }
        String qaOutput = System.getProperty("insightops.pdf.qa-output");
        if (qaOutput != null && !qaOutput.isBlank()) {
            Path output = Path.of(qaOutput).toAbsolutePath().normalize();
            Files.createDirectories(output.getParent());
            Files.write(output, pdf);
        }
    }

    private static ReportDeliveryStore.ReportItem item(int index) {
        return new ReportDeliveryStore.ReportItem(
                UUID.randomUUID(), UUID.fromString("00000000-0000-0000-0000-000000000101"),
                "Spring AI", "GITHUB_RELEASE", "v2." + index + ".0",
                "Spring AI 版本 " + index + " 发布", "https://github.com/spring-projects/spring-ai/releases/tag/v2." + index,
                index % 3 == 0 ? "HIGH" : "MEDIUM", "TRY", "SUFFICIENT",
                "本次发布增强可观测性、向量检索和聊天记忆能力，升级前需要完成 Java 兼容性验证。",
                List.of("新增结构化日志与指标接口", "改善向量数据库过滤条件", "调整聊天记忆迁移方式"),
                "现有 Java 服务应回归验证自动配置、序列化和并发调用行为。",
                "能够提高 Agent 执行过程的可解释性，并减少知识检索延迟。",
                List.of("配置项存在兼容性变化", "旧版聊天记忆数据需要迁移"),
                List.of("先在测试环境升级", "对关键问答运行回归评测", "确认监控指标后再发布"),
                List.of("https://github.com/spring-projects/spring-ai/releases/tag/v2." + index),
                NOW.minus(Duration.ofHours(index)), NOW);
    }
}
