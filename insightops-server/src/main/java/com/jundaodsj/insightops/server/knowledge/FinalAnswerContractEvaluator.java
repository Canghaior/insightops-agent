package com.jundaodsj.insightops.server.knowledge;

import com.jundaodsj.insightops.knowledge.application.AnswerContractCheck;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Checks the final answer text against an authored rubric, not tool execution metadata. */
public final class FinalAnswerContractEvaluator {
    private static final Pattern CITATION = Pattern.compile("\\[([A-Za-z]+\\d+)]");
    private FinalAnswerContractEvaluator() { }

    public static AnswerContractCheck evaluate(String answer, List<String> requiredFacts,
            List<String> forbiddenFacts, List<CitedFact> citedFacts, Collection<String> availableLabels) {
        String text = answer == null ? "" : answer;
        String normalized = normalize(text);
        List<String> missing = requiredFacts.stream().filter(value -> !normalized.contains(normalize(value))).toList();
        List<String> forbidden = forbiddenFacts.stream().filter(value -> normalized.contains(normalize(value))).toList();
        LinkedHashSet<String> citations = new LinkedHashSet<>();
        if (text.isBlank()) citations.add("FINAL_ANSWER_EMPTY");
        Set<String> available = new HashSet<>(availableLabels);
        for (String label : labels(text)) if (!available.contains(label)) citations.add("UNKNOWN_CITATION:" + label);

        // A rubric binds a required fact to an authored source label in the same sentence/line.
        // Literal text matching deliberately does not claim to understand paraphrases or entailment.
        List<String> sentences = Arrays.asList(text.split("[。！？\\r\\n]+|(?<=[.!?])\\s+"));
        for (CitedFact rule : citedFacts) {
            List<String> matching = sentences.stream().filter(sentence -> normalize(sentence).contains(normalize(rule.fact()))).toList();
            if (matching.isEmpty()) {
                citations.add("CITED_FACT_MISSING:" + rule.fact());
                continue;
            }
            for (String sentence : matching) {
                Set<String> actual = labels(sentence);
                if (actual.isEmpty() || actual.stream().noneMatch(rule.allowedLabels()::contains)
                        || actual.stream().anyMatch(label -> !rule.allowedLabels().contains(label))) {
                    citations.add("FACT_CITATION_MISMATCH:" + rule.fact());
                }
            }
        }
        return new AnswerContractCheck(missing.isEmpty() && forbidden.isEmpty() && citations.isEmpty(),
                missing, forbidden, List.copyOf(citations), "REQUIRED_TEXT_AND_CITATION_ASSOCIATION", false);
    }

    public static AnswerContractCheck requiredText(String answer, List<String> required, int evidenceCount) {
        List<String> labels = java.util.stream.IntStream.rangeClosed(1, evidenceCount).mapToObj(index -> "S" + index).toList();
        return evaluate(answer, required, List.of(), List.of(), labels);
    }

    private static Set<String> labels(String value) {
        Set<String> labels = new LinkedHashSet<>();
        var matcher = CITATION.matcher(value);
        while (matcher.find()) labels.add(matcher.group(1));
        return labels;
    }
    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }
    public record CitedFact(String fact, List<String> allowedLabels) {
        public CitedFact {
            if (fact == null || fact.isBlank() || allowedLabels == null || allowedLabels.isEmpty())
                throw new IllegalArgumentException("A cited fact requires text and allowed source labels");
            allowedLabels = List.copyOf(allowedLabels);
        }
    }
}
