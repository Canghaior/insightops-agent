package com.jundaodsj.insightops.server.knowledge;

import com.jundaodsj.insightops.knowledge.application.KnowledgeEmbeddingStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Component
public class HybridSearchRanker {
    private static final double VECTOR_WEIGHT = 0.65;
    private static final double KEYWORD_WEIGHT = 0.35;
    private static final int RRF_K = 60;

    public List<KnowledgeEmbeddingStore.SearchResult> fuse(
            String query,
            List<KnowledgeEmbeddingStore.SearchResult> vectorResults,
            List<KnowledgeEmbeddingStore.SearchResult> keywordResults,
            int limit) {
        Map<UUID, RankedResult> merged = new HashMap<>();
        add(merged, vectorResults, VECTOR_WEIGHT);
        add(merged, keywordResults, KEYWORD_WEIGHT);
        double maximum = (VECTOR_WEIGHT + KEYWORD_WEIGHT) / (RRF_K + 1.0);
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        List<KnowledgeEmbeddingStore.SearchResult> output = new ArrayList<>();
        merged.values().stream()
                .sorted((left, right) -> Double.compare(
                        boosted(right, normalizedQuery), boosted(left, normalizedQuery)))
                .limit(Math.max(1, Math.min(50, limit)))
                .forEach(value -> {
                    var item = value.result;
                    double normalizedScore = Math.min(1.0,
                            boosted(value, normalizedQuery) / maximum);
                    output.add(new KnowledgeEmbeddingStore.SearchResult(
                            item.chunkId(), item.projectId(), item.projectName(), item.sourceName(),
                            item.title(), item.canonicalUrl(), item.headingPath(), item.content(),
                            item.language(), item.trustTier(), normalizedScore, item.versionLabel(), item.collectedAt()));
                });
        return List.copyOf(output);
    }

    private static void add(Map<UUID, RankedResult> merged,
                            List<KnowledgeEmbeddingStore.SearchResult> results,
                            double weight) {
        for (int index = 0; index < results.size(); index++) {
            var result = results.get(index);
            RankedResult ranked = merged.computeIfAbsent(result.chunkId(), ignored ->
                    new RankedResult(result));
            ranked.score += weight / (RRF_K + index + 1.0);
        }
    }

    private static double boosted(RankedResult value, String query) {
        String project = value.result.projectName().toLowerCase(Locale.ROOT);
        boolean named = (project.contains("spring")
                && (query.contains("spring ai") || query.contains("spring-ai")))
                || (project.contains("langchain4j") && query.contains("langchain4j"))
                || (project.contains("dify") && query.contains("dify"));
        String version = value.result.versionLabel();
        boolean versionNamed = version != null && java.util.regex.Pattern.compile(
                "(?<![a-z0-9.])v?" + java.util.regex.Pattern.quote(version.toLowerCase(Locale.ROOT)) + "(?![a-z0-9.])")
                .matcher(query).find();
        boolean changelog = query.contains("changelog") && value.result.canonicalUrl().endsWith("/CHANGELOG.md");
        return value.score * (named ? 1.08 : 1.0) * (versionNamed ? 1.25 : 1.0) * (changelog ? 1.15 : 1.0);
    }

    private static final class RankedResult {
        private final KnowledgeEmbeddingStore.SearchResult result;
        private double score;

        private RankedResult(KnowledgeEmbeddingStore.SearchResult result) {
            this.result = result;
        }
    }
}
