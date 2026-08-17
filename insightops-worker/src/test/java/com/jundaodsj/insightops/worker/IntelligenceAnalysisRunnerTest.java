package com.jundaodsj.insightops.worker;

import com.jundaodsj.insightops.infrastructure.model.DeepSeekCostEstimator;
import com.jundaodsj.insightops.intelligence.application.IntelligenceStore;
import com.jundaodsj.insightops.model.application.ChatModelResponse;
import com.jundaodsj.insightops.model.application.ModelUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntelligenceAnalysisRunnerTest {
    private static final Instant NOW = Instant.parse("2026-08-17T09:00:00Z");
    private final IntelligenceStore store = mock(IntelligenceStore.class);
    private final ReleaseIntelligenceAnalyzer analyzer = mock(ReleaseIntelligenceAnalyzer.class);
    private final DeepSeekCostEstimator estimator = mock(DeepSeekCostEstimator.class);
    private final IntelligenceAnalysisProperties properties = new IntelligenceAnalysisProperties();
    private final IntelligenceStore.AnalysisTask task = new IntelligenceStore.AnalysisTask(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "spring-projects", "spring-ai", "v2.2.0", "Spring AI 2.2.0", "Release notes",
            "https://github.com/spring-projects/spring-ai/releases/tag/v2.2.0",
            NOW.minus(Duration.ofHours(1)), 1, 3, true);
    private IntelligenceAnalysisRunner runner;

    @BeforeEach
    void setUp() {
        runner = new IntelligenceAnalysisRunner(store, analyzer, estimator, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(analyzer.available()).thenReturn(true);
        when(store.claimDueAnalyses(eq(NOW), any(), anyInt(), anyInt())).thenReturn(List.of(task));
    }

    @Test
    void shouldCompleteAValidStructuredAnalysis() {
        var result = new IntelligenceStore.AnalysisResult(
                "MEDIUM", "TRY", "SUFFICIENT", "建议先试用", List.of("新增能力"),
                "需要验证 Java 兼容性", "提升可观测性", List.of("迁移成本"),
                List.of("测试环境验证"), List.of(task.sourceUrl()));
        var response = new ChatModelResponse("{}", "deepseek", "deepseek-v4-flash",
                new ModelUsage(300, 120, 420, 0L, 0L), Duration.ofMillis(500));
        when(analyzer.analyze(task)).thenReturn(new ReleaseIntelligenceAnalyzer.AnalyzedRelease(result, response));
        when(estimator.estimate(response.usage())).thenReturn(Optional.empty());

        var cycle = runner.analyzeDueReleases();

        assertThat(cycle.claimed()).isEqualTo(1);
        assertThat(cycle.succeeded()).isEqualTo(1);
        verify(store).completeAnalysis(eq(task), eq(result), any(), eq(NOW));
        verify(store, never()).failAnalysis(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void shouldRetryTransientFailureButTerminateInvalidModelOutput() {
        when(analyzer.analyze(task)).thenThrow(new IllegalStateException("temporary"));

        var transientCycle = runner.analyzeDueReleases();

        assertThat(transientCycle.failed()).isEqualTo(1);
        verify(store).failAnalysis(eq(task), eq("INTERNAL_ERROR"), eq("temporary"), eq(NOW),
                eq(NOW.plus(Duration.ofMinutes(5))), eq(false));

        var invalidTask = new IntelligenceStore.AnalysisTask(
                task.analysisId(), task.workspaceId(), task.projectId(), task.eventId(),
                task.repositoryOwner(), task.repositoryName(), task.versionTag(), task.releaseTitle(),
                task.releaseSummary(), task.sourceUrl(), task.occurredAt(), 1, 3, true);
        reset(analyzer);
        when(analyzer.available()).thenReturn(true);
        when(store.claimDueAnalyses(eq(NOW), any(), anyInt(), anyInt())).thenReturn(List.of(invalidTask));
        doThrow(new ReleaseIntelligenceAnalyzer.InvalidAnalysisException("invalid", new IllegalArgumentException()))
                .when(analyzer).analyze(invalidTask);

        runner.analyzeDueReleases();

        verify(store).failAnalysis(eq(invalidTask), eq("INVALID_OUTPUT"), eq("invalid"), eq(NOW),
                any(), eq(true));
    }
}
