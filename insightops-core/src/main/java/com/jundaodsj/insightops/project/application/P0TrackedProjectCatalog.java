package com.jundaodsj.insightops.project.application;

import java.util.List;
import java.util.Optional;

public final class P0TrackedProjectCatalog {

    private static final List<ProjectDefinition> PROJECTS = List.of(
            new ProjectDefinition(
                    "spring-ai",
                    "Spring AI",
                    "spring-projects",
                    "spring-ai",
                    List.of("spring ai", "spring-ai")),
            new ProjectDefinition(
                    "langchain4j",
                    "LangChain4j",
                    "langchain4j",
                    "langchain4j",
                    List.of("langchain4j")),
            new ProjectDefinition(
                    "dify",
                    "Dify",
                    "langgenius",
                    "dify",
                    List.of("dify")));

    private P0TrackedProjectCatalog() {
    }

    public static List<ProjectDefinition> all() {
        return PROJECTS;
    }

    public static Optional<ProjectDefinition> find(String projectId) {
        return PROJECTS.stream()
                .filter(project -> project.id().equals(projectId))
                .findFirst();
    }

    public record ProjectDefinition(
            String id,
            String displayName,
            String repositoryOwner,
            String repositoryName,
            List<String> aliases) {

        public ProjectDefinition {
            aliases = List.copyOf(aliases);
        }
    }
}
