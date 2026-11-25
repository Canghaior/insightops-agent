package com.jundaodsj.insightops.server.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.agent.application.AgentRunQuery.RunToolCall;
import com.jundaodsj.insightops.conversation.application.ChatCitation;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ClaimPassagesTest {
    @Test void mapsRelabeledChunksBySourceAndSectionAndRejectsAmbiguousIdentity() {
        var call = mock(RunToolCall.class);
        when(call.status()).thenReturn("SUCCEEDED");
        when(call.toolName()).thenReturn("knowledge_hybrid_search");
        when(call.resultPayload()).thenReturn(Map.of("sources", List.of(Map.of("citation","S1","sourceUrl","https://docs.example/a",
                "heading","金额","version","1.1.0","passage","金额小于等于零时拒绝。"))));
        var citation = new ChatCitation("S2", "金额规则", "https://docs.example/a","样例","金额","OFFICIAL_DOCUMENT", 1.0, "1.1.0", null);
        assertThat(ClaimPassages.from(call, List.of(citation), new ObjectMapper())).singleElement()
                .satisfies(p -> { assertThat(p.label()).isEqualTo("S2"); assertThat(p.text()).contains("等于零"); });
        assertThat(ClaimPassages.from(call, List.of(citation,citation), new ObjectMapper())).isEmpty();
        assertThat(ReportClaimReviews.matchesTarget("1.1", "1.1.0")).isFalse();
        assertThat(ReportClaimReviews.matchesTarget("v1.1.0-RC1", "1.1.0")).isFalse();
        assertThat(ReportClaimReviews.matchesTarget("v1.1.0", "1.1.0")).isTrue();
    }
}
