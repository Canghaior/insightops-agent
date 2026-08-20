package com.jundaodsj.insightops.server.report;

import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.report.application.ReportDeliveryStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-20T02:00:00Z");
    private static final ActorContext ACTOR = new ActorContext(UUID.randomUUID(), UUID.randomUUID());
    private final ReportDeliveryStore store = mock(ReportDeliveryStore.class);
    private final ReportService service = new ReportService(store, mock(ReportPdfRenderer.class));

    @Test
    void validatesRangeAndRequiresMatchingCompletedIntelligence() {
        assertThatThrownBy(() -> service.create(ACTOR, "report", NOW.minus(Duration.ofDays(367)),
                NOW, List.of(), List.of(), 50, NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("366");
        assertThatThrownBy(() -> service.create(ACTOR, "report", NOW.minus(Duration.ofDays(7)),
                NOW, List.of(), List.of("UNKNOWN"), 50, NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("event type");

        when(store.selectReportItems(any(), any())).thenReturn(List.of());
        assertThatThrownBy(() -> service.create(ACTOR, "report", NOW.minus(Duration.ofDays(7)),
                NOW, List.of(), List.of(), 50, NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No completed");
    }

    @Test
    void snapshotsRenderedMarkdownWhenCreatingReport() {
        var item = sampleItem();
        when(store.selectReportItems(any(), any())).thenReturn(List.of(item));

        service.create(ACTOR, "Release intelligence", NOW.minus(Duration.ofDays(7)), NOW,
                List.of(item.projectId()), List.of("github_release"), 150, NOW);

        verify(store).createReport(any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.argThat(markdown -> markdown.contains("Release intelligence")
                        && markdown.contains(item.sourceUrl())), any());
    }

    private static ReportDeliveryStore.ReportItem sampleItem() {
        return new ReportDeliveryStore.ReportItem(
                UUID.randomUUID(), UUID.randomUUID(), "Spring AI", "GITHUB_RELEASE", "v2.2.0",
                "Spring AI 2.2.0", "https://github.com/spring-projects/spring-ai/releases/tag/v2.2.0",
                "HIGH", "TRY", "SUFFICIENT", "聊天记忆迁移需要验证。", List.of("新增观测 API"),
                "需要兼容性测试", "提升可观测性", List.of("存在破坏性变更"), List.of("测试环境升级"),
                List.of("https://github.com/spring-projects/spring-ai/releases/tag/v2.2.0"),
                NOW.minus(Duration.ofDays(1)), NOW);
    }
}
