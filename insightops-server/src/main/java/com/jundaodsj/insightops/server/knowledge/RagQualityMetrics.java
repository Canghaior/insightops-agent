package com.jundaodsj.insightops.server.knowledge;

import com.jundaodsj.insightops.knowledge.application.KnowledgeEmbeddingStore;
import com.jundaodsj.insightops.knowledge.application.RagEvaluationStore;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RagQualityMetrics {
    private static final Pattern CITATION = Pattern.compile("\\[(S\\d+)]");

    private RagQualityMetrics() { }

    public static RetrievalScore retrieval(RagEvaluationDataset.EvaluationCase item,
                                           KnowledgeSearchService.SearchResponse response,
                                           KnowledgeAnswerabilityPolicy policy) {
        List<KnowledgeEmbeddingStore.SearchResult> results = response.results();
        var assessment = policy.assess(item.question(), results);
        int rank = firstProjectRank(results, item.expectedProject());
        boolean projectHit = item.answerable() && rank > 0;
        double reciprocalRank = rank == 0 ? 0.0 : 1.0 / rank;
        double termCoverage = item.answerable()
                ? termCoverage(results, item.mustHitTerms()) : 1.0;
        List<String> projects = results.stream().limit(10)
                .map(KnowledgeEmbeddingStore.SearchResult::projectName).toList();
        List<String> urls = results.stream().limit(10)
                .map(KnowledgeEmbeddingStore.SearchResult::canonicalUrl).distinct().toList();
        return new RetrievalScore(assessment.answerable(),
                assessment.answerable() == item.answerable(), projectHit,
                reciprocalRank, termCoverage, projects, urls);
    }

    public static CitationScore citations(String answer, int evidenceCount) {
        Matcher matcher = CITATION.matcher(answer == null ? "" : answer);
        int references = 0;
        int valid = 0;
        Set<String> used = new LinkedHashSet<>();
        while (matcher.find()) {
            references++;
            int index = Integer.parseInt(matcher.group(1).substring(1));
            if (index >= 1 && index <= evidenceCount) {
                valid++;
                used.add(matcher.group(1));
            }
        }
        double precision = references == 0 ? 0.0 : (double) valid / references;
        double coverage = evidenceCount == 0 ? 1.0 : (double) used.size() / evidenceCount;
        return new CitationScore(precision, coverage);
    }

    public static RagEvaluationStore.Summary aggregate(
            List<RagEvaluationStore.CaseResult> cases,
            RagEvaluationProperties properties,
            String modelName) {
        List<RagEvaluationStore.CaseResult> answerable = cases.stream()
                .filter(RagEvaluationStore.CaseResult::expectedAnswerable).toList();
        List<RagEvaluationStore.CaseResult> negatives = cases.stream()
                .filter(item -> !item.expectedAnswerable()).toList();
        List<RagEvaluationStore.CaseResult> generated = cases.stream()
                .filter(item -> item.citationPrecision() != null).toList();
        double recall = average(answerable, item -> item.projectHit() ? 1.0 : 0.0);
        double mrr = average(answerable, RagEvaluationStore.CaseResult::reciprocalRank);
        double termCoverage = average(answerable, RagEvaluationStore.CaseResult::termCoverage);
        double noAnswer = average(negatives, item -> item.answerabilityCorrect() ? 1.0 : 0.0);
        Double citationPrecision = generated.isEmpty() ? null
                : average(generated, item -> item.citationPrecision());
        Double citationCoverage = generated.isEmpty() ? null
                : average(generated, item -> item.citationCoverage());
        List<RagEvaluationStore.CaseResult> judged = generated.stream()
                .filter(item -> item.faithfulness() != null).toList();
        Double faithfulness = judged.isEmpty() ? null
                : average(judged, item -> item.faithfulness());
        boolean passed = recall >= properties.getMinimumRecallAtK()
                && mrr >= properties.getMinimumMrr()
                && termCoverage >= properties.getMinimumTermCoverage()
                && noAnswer >= properties.getMinimumNoAnswerAccuracy()
                && (citationPrecision == null || citationPrecision >= properties.getMinimumCitationPrecision())
                && (citationCoverage == null || citationCoverage >= properties.getMinimumCitationCoverage())
                && (faithfulness == null || faithfulness >= properties.getMinimumFaithfulness());
        return new RagEvaluationStore.Summary(recall, mrr, recall, termCoverage, noAnswer,
                citationPrecision, citationCoverage, faithfulness, passed, modelName);
    }

    private static int firstProjectRank(List<KnowledgeEmbeddingStore.SearchResult> results,
                                        String expectedProject) {
        if (expectedProject == null) return 0;
        String expected = normalizeProject(expectedProject);
        for (int index = 0; index < Math.min(10, results.size()); index++) {
            String project = results.get(index).projectName();
            if (project != null && normalizeProject(project).contains(expected)) return index + 1;
        }
        return 0;
    }

    private static String normalizeProject(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static double termCoverage(List<KnowledgeEmbeddingStore.SearchResult> results,
                                       List<String> terms) {
        if (terms.isEmpty()) return 1.0;
        String corpus = results.stream().limit(10)
                .map(item -> item.title() + "\n" + item.headingPath() + "\n" + item.content())
                .reduce("", (left, right) -> left + "\n" + right)
                .toLowerCase(Locale.ROOT);
        long matched = terms.stream().map(term -> term.toLowerCase(Locale.ROOT))
                .filter(corpus::contains).count();
        return (double) matched / terms.size();
    }

    private static double average(List<RagEvaluationStore.CaseResult> values,
                                  java.util.function.ToDoubleFunction<RagEvaluationStore.CaseResult> metric) {
        return values.isEmpty() ? 1.0 : values.stream().mapToDouble(metric).average().orElse(0.0);
    }

    public record RetrievalScore(boolean predictedAnswerable, boolean answerabilityCorrect,
                                 boolean projectHit, double reciprocalRank, double termCoverage,
                                 List<String> topProjects, List<String> sourceUrls) { }

    public record CitationScore(double precision, double coverage) { }
}
