package com.jundaodsj.insightops.server.report;

import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.report.application.BuildEvidenceComparison;
import com.jundaodsj.insightops.report.application.ReportReviewStore;
import com.jundaodsj.insightops.report.application.ReportReviewStore.*;
import com.jundaodsj.insightops.server.workflow.ResearchMaterials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportReviewServiceTest {
    private final UUID report = UUID.randomUUID(), request = UUID.randomUUID();
    private final ActorContext actor = new ActorContext(UUID.randomUUID(), UUID.randomUUID());
    private final ReportReviewStore store = mock(ReportReviewStore.class);
    private final ResearchMaterials materials = mock(ResearchMaterials.class);
    private final BuildComparisonService comparisons = mock(BuildComparisonService.class);
    private final ReportReviewService service = new ReportReviewService(store, materials, comparisons);
    @BeforeEach void scope() {
        when(store.context(actor, report, true)).thenReturn(Optional.of(
                new Context(report, UUID.randomUUID(), UUID.randomUUID(), 1, null, null, null)));
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
            NOT_EXECUTED,SUPPORTS,plan,,,,
            NOT_EXECUTED,INCONCLUSIVE,plan,,observed,,
            NOT_EXECUTED,INCONCLUSIVE,,,,,
            BLOCKED,INCONCLUSIVE,,,,,
            BLOCKED,CONTRADICTS,,,,,blocked
            BLOCKED,INCONCLUSIVE,,,observed,,blocked
            EXECUTED,INCONCLUSIVE,,windows,visible,local,
            EXECUTED,INCONCLUSIVE,test,,visible,local,
            EXECUTED,INCONCLUSIVE,test,windows,,local,
            EXECUTED,SUPPORTS,test,windows,visible,,
            EXECUTED,SUPPORTS,test,windows,visible,local,blocked
            """)
    void rejectsInconsistentExecution(Execution status, Impact impact, String method, String env,
                                      String observation, String limits, String blocker) {
        var fields = new VerificationInput("字体影响？","建议 3",Origin.MODEL,status,method,env,
                observation,impact,limits,blocker,List.of());
        assertThatIllegalArgumentException().isThrownBy(() -> service.record(actor, report,
                new ReportReviewService.VerificationCommand(request, null, fields)));
        verifyNoInteractions(materials);
        verify(store, never()).addVerification(any(),any(),any(),any(),any(),anyInt(),any(),any());
    }

    @Test void exactReplaySurvivesLaterLossOfUploadAccessButChangedPayloadIsRejected() {
        var fields = new VerificationInput("字体影响？","建议 3",Origin.MODEL,Execution.NOT_EXECUTED,
                "对照","", "",Impact.INCONCLUSIVE,"","",List.of(UUID.randomUUID()));
        var saved = new Verification(UUID.randomUUID(),null,1,fields,new Author(actor.userId(),"记录者"),Instant.now(),null);
        when(store.verificationRequest(report,request)).thenReturn(Optional.of(saved));
        assertThat(service.record(actor, report, new ReportReviewService.VerificationCommand(request,null,fields))).isEqualTo(saved);
        assertThatThrownBy(() -> service.record(actor,report,new ReportReviewService.VerificationCommand(request,saved.id(),fields)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        verifyNoInteractions(materials);
    }

    @Test void decisionsNeedAnExactVersionEvenWhenDeferring() {
        var fields = new DecisionInput("暂缓","缺阅读器","取得版本后重测",List.of());
        assertThatIllegalArgumentException().isThrownBy(() -> service.decide(actor, report,
                new ReportReviewService.DecisionCommand(request, fields)));
    }

    private static VerificationInput buildFields(UUID baseline, UUID candidate) {
        return new VerificationInput("能否合并？","核对 H2 回归",Origin.MODEL,Execution.EXECUTED,
                "复核当时留存的原件","历史 H2 环境","记录者自己的观察",Impact.INCONCLUSIVE,
                "仅覆盖给出的范围","",List.of(),baseline,candidate,"外部执行者");
    }

    @Test void onlyTheServerSuppliesTheFrozenBuildSummary() {
        var fields = buildFields(UUID.randomUUID(), UUID.randomUUID());
        var compared = mock(BuildEvidenceComparison.class);
        when(compared.objectiveSummary()).thenReturn("基线 54 项，候选报错 2 项");
        when(comparisons.compare(actor, report, fields.baselineBuildId(), fields.candidateBuildId())).thenReturn(compared);
        var saved = new Verification(UUID.randomUUID(), null, 1, fields, new Author(actor.userId(), "记录者"),
                Instant.now(), compared.objectiveSummary());
        when(store.verification(eq(report), any())).thenReturn(Optional.of(saved));
        assertThat(service.record(actor, report, new ReportReviewService.VerificationCommand(request, null, fields)))
                .isEqualTo(saved);
        verify(store).addVerification(any(), eq(actor), any(), eq(request), isNull(), eq(1), eq(fields),
                eq("基线 54 项，候选报错 2 项"));
    }

    @Test void retriesKeepTheOriginalBatchesAndDoNotRecomputeHistory() {
        var old = buildFields(UUID.randomUUID(), UUID.randomUUID());
        var saved = new Verification(UUID.randomUUID(), null, 1, old, new Author(actor.userId(), "记录者"),
                Instant.now(), "当时保存的客观摘要");
        when(store.verificationRequest(report, request)).thenReturn(Optional.of(saved));
        assertThat(service.record(actor, report, new ReportReviewService.VerificationCommand(request, null, old))).isEqualTo(saved);
        var swapped = buildFields(UUID.randomUUID(), old.candidateBuildId());
        assertThatThrownBy(() -> service.record(actor, report, new ReportReviewService.VerificationCommand(request, null, swapped)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        verifyNoInteractions(comparisons, materials);
    }

    @Test void requiresBothBuildsAndDoesNotInventAnExecutorForAnUnexecutedPlan() {
        var partial = buildFields(UUID.randomUUID(), null);
        assertThatIllegalArgumentException().isThrownBy(() ->
                service.record(actor, report, new ReportReviewService.VerificationCommand(request, null, partial)))
                .withMessageContaining("同时选择");
        var plan = new VerificationInput("问题","建议",Origin.MODEL,Execution.NOT_EXECUTED,
                "计划","", "",Impact.INCONCLUSIVE,"","",List.of(),null,null,"外部执行者");
        assertThatIllegalArgumentException().isThrownBy(() ->
                service.record(actor, report, new ReportReviewService.VerificationCommand(request, null, plan)))
                .withMessageContaining("执行者");
        verifyNoInteractions(comparisons, materials);
    }
}
