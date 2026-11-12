package com.jundaodsj.insightops.server.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.agent.application.AgentRunQuery;
import com.jundaodsj.insightops.conversation.application.ChatCitation;
import com.jundaodsj.insightops.report.application.ReportDeliveryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResearchEvidenceQualityTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final ResearchEvidenceQuality quality = new ResearchEvidenceQuality(json);

    static Stream<JsonNode> fixedUpgradeCases() throws Exception {
        try (var input = ResearchEvidenceQualityTest.class.getResourceAsStream("/evidence-quality/upgrade-cases.json")) {
            List<JsonNode> cases = new ArrayList<>();
            new ObjectMapper().readTree(input).forEach(cases::add);
            return cases.stream();
        }
    }

    @ParameterizedTest(name = "{0}") @MethodSource("fixedUpgradeCases")
    void fixedJavaAndVueEvidenceCannotBecomeACompatibilityCertificate(JsonNode fixture) {
        var run = mock(AgentRunQuery.RunDetail.class);
        when(run.status()).thenReturn("SUCCEEDED");
        when(run.answer()).thenReturn(fixture.path("answer").asText("固定证据研究结论 [S1]"));
        List<ChatCitation> citations = new ArrayList<>();
        fixture.path("versions").forEach(version -> citations.add(new ChatCitation("S" + (citations.size() + 1),
                "固定版本来源", "https://example.com/" + version.asText(), fixture.path("project").asText(),
                "CHANGELOG", "OFFICIAL_DOCUMENT", 1.0, version.asText(), Instant.EPOCH)));
        when(run.citationDetails()).thenReturn(citations);
        when(run.toolCalls()).thenReturn(List.of(tool(fixture.path("toolStatus").asText("SUCCEEDED"),
                Map.of("truncated", fixture.path("truncated").asBoolean(false), "range",
                        Map.of("complete", true, "currentVersionFound", true,
                                "targetVersionFound", fixture.path("targetFound").asBoolean(true))))));
        var result = quality.assess(run, Map.of("currentVersion", fixture.path("current").asText(),
                "targetVersion", fixture.path("target").asText()), List.of());
        assertThat(result.status()).as(fixture.path("id").asText()).isEqualTo(fixture.path("expectedStatus").asText());
        assertThat(result.reasons()).extracting("code").contains(fixture.path("expectedCode").asText());
        assertThat(result.compatibilityVerified()).isFalse();
        assertThat(result.summary()).contains("尚未逐条核验结论", "不构成兼容性保证");
    }

    @Test void incompleteExecutionIsNotAssessedAndAnUnreferencedAnswerIsInsufficient() {
        var run = mock(AgentRunQuery.RunDetail.class);
        when(run.status()).thenReturn("RUNNING");
        assertThat(quality.assess(run, Map.of(), List.of()).status()).isEqualTo("NOT_ASSESSED");
        when(run.status()).thenReturn("SUCCEEDED");
        when(run.answer()).thenReturn("没有任何引用的肯定结论");
        assertThat(quality.assess(run, Map.of(), List.of()).status()).isEqualTo("INSUFFICIENT_EVIDENCE");
    }

    @Test void reviewReadyMeansCompletenessOnlyAndDoesNotJudgeClaimTruth() {
        var run = mock(AgentRunQuery.RunDetail.class);
        when(run.status()).thenReturn("SUCCEEDED");
        // Deliberately unsupported claim: the rules must not pretend they have semantically verified it.
        when(run.answer()).thenReturn("任何项目升级都保证兼容 [S1]");
        when(run.citationDetails()).thenReturn(List.of(new ChatCitation("S1", "资料", "https://example.com", "Vue", "", "OFFICIAL_DOCUMENT", 1.0)));
        var result = quality.assess(run, Map.of(), List.of());
        assertThat(result.status()).isEqualTo("REVIEW_READY");
        assertThat(result.label()).isEqualTo("可进入人工核查");
        assertThat(result.compatibilityVerified()).isFalse();
        assertThat(result.summary()).contains("尚未逐条核验结论");
    }

    @Test void historicalReportWithoutQualityRemainsUnassessedAndIsNotBackfilled() throws Exception {
        var historical = json.readValue("""
                {"runId":"10000000-0000-0000-0000-000000000001","question":"原始问题","answer":"历史答案", "inputs":{},
                 "citations":[],"sources":[],"uncertainties":["历史缺口"],"evidence":[],"startedAt":null,"finishedAt":null}
                """, ReportDeliveryStore.ResearchSnapshot.class);
        assertThat(historical.evidenceQuality()).isNull();
        assertThat(historical.answer()).isEqualTo("历史答案");
        assertThat(historical.uncertainties()).containsExactly("历史缺口");
    }

    @Test void releaseAndIssueCitationPrefixesAreRecognizedWithoutAssumingEveryCitationIsS() {
        var run = mock(AgentRunQuery.RunDetail.class);
        when(run.status()).thenReturn("SUCCEEDED");
        when(run.answer()).thenReturn("发布资料 [R1]，已知问题 [I1]");
        when(run.citationDetails()).thenReturn(List.of(
                new ChatCitation("R1", "Release", "https://example.com/release", "Vue", "", "GITHUB_RELEASE", null),
                new ChatCitation("I1", "Issue", "https://example.com/issue", "Vue", "", "GITHUB_ISSUE", null)));
        assertThat(quality.assess(run, Map.of(), List.of()).reasons()).extracting("code")
                .doesNotContain("ANSWER_CITATIONS_MISSING", "UNKNOWN_CITATION");
    }

    @Test void absentRangeMetadataAndUnparseableVersionsAreNeverAssumedComplete() {
        var run = mock(AgentRunQuery.RunDetail.class);
        when(run.status()).thenReturn("SUCCEEDED");
        when(run.answer()).thenReturn("研究 [R1]");
        when(run.citationDetails()).thenReturn(List.of(new ChatCitation("R1", "Release", "https://example.com/release",
                "Vue", "", "GITHUB_RELEASE", null, "3.5.1", Instant.EPOCH)));
        when(run.toolCalls()).thenReturn(List.of(tool("SUCCEEDED", Map.of("releaseCount", 1))));
        var missing = quality.assess(run, Map.of("currentVersion", "3.4.38", "targetVersion", "3.5.1"), List.of());
        assertThat(missing.status()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(missing.reasons()).extracting("code").contains("VERSION_RANGE_UNRECORDED");
        var unknown = quality.assess(run, Map.of("currentVersion", "3.4.38", "targetVersion", "latest"), List.of());
        assertThat(unknown.reasons()).extracting("code").contains("VERSION_FORMAT_UNCONFIRMED");
    }

    private static AgentRunQuery.RunToolCall tool(String status, Map<String, Object> output) {
        return new AgentRunQuery.RunToolCall(UUID.randomUUID(), UUID.randomUUID(), "github_release_list", status,
                Map.of(), output, null, 1L, Instant.EPOCH, Instant.EPOCH, List.of());
    }
}
