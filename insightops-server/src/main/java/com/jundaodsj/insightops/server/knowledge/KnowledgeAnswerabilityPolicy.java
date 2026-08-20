package com.jundaodsj.insightops.server.knowledge;

import com.jundaodsj.insightops.knowledge.application.KnowledgeEmbeddingStore;
import com.jundaodsj.insightops.project.application.AdminProjectStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Component
public class KnowledgeAnswerabilityPolicy {
    private final AdminProjectStore projectStore;

    public KnowledgeAnswerabilityPolicy() {
        this.projectStore = null;
    }

    @Autowired
    public KnowledgeAnswerabilityPolicy(AdminProjectStore projectStore) {
        this.projectStore = projectStore;
    }

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

    public Assessment assess(UUID workspaceId, String query,
                             List<KnowledgeEmbeddingStore.SearchResult> results) {
        if (results == null || results.isEmpty()) return new Assessment(false, List.of());
        if (results.stream().limit(5).anyMatch(result -> "T2_USER_UPLOAD".equals(result.trustTier()))) {
            return new Assessment(true, List.of("user-upload"));
        }
        if (projectStore == null || workspaceId == null) return assess(query, results);
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        List<AdminProjectStore.ManagedProject> referenced = projectStore.list(workspaceId).stream()
                .filter(AdminProjectStore.ManagedProject::enabled)
                .filter(project -> aliases(project).stream().anyMatch(normalized::contains))
                .limit(5)
                .toList();
        Set<UUID> projectIds = referenced.stream()
                .map(AdminProjectStore.ManagedProject::projectId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        boolean matched = !projectIds.isEmpty() && results.stream().limit(5)
                .map(KnowledgeEmbeddingStore.SearchResult::projectId)
                .anyMatch(projectIds::contains);
        return new Assessment(matched, referenced.stream()
                .map(project -> project.projectId().toString()).toList());
    }

    private static List<String> aliases(AdminProjectStore.ManagedProject project) {
        List<String> values = new ArrayList<>();
        values.add(project.repositoryName());
        values.add(project.repositoryOwner() + "/" + project.repositoryName());
        values.add(project.repositoryName().replace('-', ' ').replace('_', ' '));
        values.addAll(project.chatAliases());
        return values.stream().filter(value -> value != null && value.length() >= 2)
                .map(value -> value.toLowerCase(Locale.ROOT)).distinct().toList();
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
