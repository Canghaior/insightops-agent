package com.jundaodsj.insightops.server.report;

import com.jundaodsj.insightops.identity.application.ActorContext;
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
    private final ReportReviewService service = new ReportReviewService(store, materials);
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
        verify(store, never()).addVerification(any(),any(),any(),any(),any(),anyInt(),any());
    }

    @Test void exactReplaySurvivesLaterLossOfUploadAccessButChangedPayloadIsRejected() {
        var fields = new VerificationInput("字体影响？","建议 3",Origin.MODEL,Execution.NOT_EXECUTED,
                "对照","", "",Impact.INCONCLUSIVE,"","",List.of(UUID.randomUUID()));
        var saved = new Verification(UUID.randomUUID(),null,1,fields,new Author(actor.userId(),"记录者"),Instant.now());
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
}
