package com.jundaodsj.insightops.server.knowledge;

import com.jundaodsj.insightops.knowledge.application.KnowledgeEmbeddingStore;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class KnowledgeAnswerabilityPolicy {

    public Assessment assess(String query, List<KnowledgeEmbeddingStore.SearchResult> results) {
        Set<String> projects = inferredProjects(query);
        if (projects.isEmpty() || results == null || results.isEmpty()) {
            return new Assessment(false, List.copyOf(projects));
        }
        boolean matched = results.stream().limit(5)
                .map(KnowledgeEmbeddingStore.SearchResult::projectName)
                .map(KnowledgeAnswerabilityPolicy::normalizeProject)
                .anyMatch(projects::contains);
        return new Assessment(matched, List.copyOf(projects));
    }

    Set<String> inferredProjects(String query) {
        String value = query == null ? "" : query.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> projects = new LinkedHashSet<>();
        if (containsAny(value, "spring ai", "spring-ai", "embeddingmodel", "chatclient",
                "advisor", "vectorstore", "spring.ai.")) {
            projects.add("spring-ai");
        }
        if (containsAny(value, "langchain4j", "ai services", "chatmemory")) {
            projects.add("langchain4j");
        }
        if (value.contains("dify")) {
            projects.add("dify");
        }
        return Set.copyOf(projects);
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    private static String normalizeProject(String value) {
        if (value == null) return "";
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.contains("spring")) return "spring-ai";
        if (normalized.contains("langchain4j")) return "langchain4j";
        if (normalized.contains("dify")) return "dify";
        return normalized;
    }

    public record Assessment(boolean answerable, List<String> inferredProjects) { }
}
