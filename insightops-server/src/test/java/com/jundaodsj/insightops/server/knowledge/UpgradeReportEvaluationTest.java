package com.jundaodsj.insightops.server.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.knowledge.application.AnswerContractCheck;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class UpgradeReportEvaluationTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void fixedFinalReportMutationsFailAgainstTheSameEvidence() throws Exception {
        var suite = suite();
        assertThat(suite.cases()).hasSizeGreaterThanOrEqualTo(2);
        for (var item : suite.cases()) {
            assertThat(evaluate(item, item.referenceAnswer()).passed()).as(item.id() + " positive final report").isTrue();
            for (var negative : item.negativeAnswers()) {
                var result = evaluate(item, negative.answer());
                assertThat(result.passed()).as(item.id() + "/" + negative.id()).isFalse();
                switch (negative.expectedFailure()) {
                    case "forbidden" -> assertThat(result.forbiddenMatches()).isNotEmpty();
                    case "citation" -> assertThat(result.citationViolations()).isNotEmpty();
                    case "required" -> assertThat(result.missingRequired()).isNotEmpty();
                    default -> fail("Unknown expected failure: " + negative.expectedFailure());
                }
                assertThat(result.semanticVerified()).isFalse();
            }
        }
    }

    /** Opt-in harness: reads already generated final answers; never calls a model or changes production data. */
    @Test @EnabledIfSystemProperty(named = "upgrade.report.answers", matches = ".+")
    void evaluateSuppliedFinalReports() throws Exception {
        var answers = json.readValue(Files.readString(Path.of(System.getProperty("upgrade.report.answers"))), SuppliedAnswer[].class);
        Map<String, String> byId = new LinkedHashMap<>();
        for (var answer : answers) assertThat(byId.put(answer.caseId(), answer.answer())).as("duplicate caseId").isNull();
        var suite = suite();
        assertThat(byId.keySet()).containsExactlyInAnyOrderElementsOf(suite.cases().stream().map(Case::id).toList());
        Map<String, AnswerContractCheck> results = new LinkedHashMap<>();
        suite.cases().forEach(item -> results.put(item.id(), evaluate(item, byId.get(item.id()))));
        Path report = Path.of("target", "upgrade-report-evaluation.json");
        Files.createDirectories(report.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), Map.of(
                "dataset", suite.version(), "scope", "规则回归，不代表语义验证或真实发布事实", "results", results));
        assertThat(results.values()).as("See target/upgrade-report-evaluation.json").allMatch(AnswerContractCheck::passed);
    }

    private AnswerContractCheck evaluate(Case item, String answer) {
        return FinalAnswerContractEvaluator.evaluate(answer, item.requiredFacts(), item.forbiddenFacts(),
                item.citedFacts(), item.evidence().stream().map(Evidence::label).toList());
    }
    private Suite suite() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("docs/evals/upgrade-report-contracts.json"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Unable to find upgrade report fixtures");
        return json.readValue(root.resolve("docs/evals/upgrade-report-contracts.json").toFile(), Suite.class);
    }
    record Suite(String version, String scope, List<Case> cases) { }
    record Case(String id, String question, List<Evidence> evidence, List<String> requiredFacts,
                List<String> forbiddenFacts, List<FinalAnswerContractEvaluator.CitedFact> citedFacts,
                String referenceAnswer, List<NegativeAnswer> negativeAnswers) { }
    record Evidence(String label, String version, String text) { }
    record NegativeAnswer(String id, String answer, String expectedFailure) { }
    record SuppliedAnswer(String caseId, String answer) { }
}
