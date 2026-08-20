package com.jundaodsj.insightops.server.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class RagEvaluationDataset {
    public static final String NAME = "p1-rag-questions-v3-50";
    private final ObjectMapper json;

    public RagEvaluationDataset(ObjectMapper json) {
        this.json = json;
    }

    public List<EvaluationCase> load() {
        ClassPathResource resource = new ClassPathResource("evals/p1-rag-questions.jsonl");
        List<EvaluationCase> cases = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) cases.add(json.readValue(line, EvaluationCase.class));
            }
        }
        catch (Exception exception) {
            throw new IllegalStateException("Unable to load the RAG evaluation dataset", exception);
        }
        return List.copyOf(cases);
    }

    public record EvaluationCase(
            String id,
            String question,
            boolean answerable,
            String expectedProject,
            String category,
            List<String> mustHitTerms,
            List<String> answerMustInclude,
            String sourceDomain,
            String status) {
        public EvaluationCase {
            mustHitTerms = mustHitTerms == null ? List.of() : List.copyOf(mustHitTerms);
            answerMustInclude = answerMustInclude == null ? List.of() : List.copyOf(answerMustInclude);
        }
    }
}
