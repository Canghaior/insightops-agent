package com.jundaodsj.insightops.tool.application.github;

public record GitHubRepositoryReleaseQuery(
        String projectId,
        String displayName,
        String repositoryOwner,
        String repositoryName,
        Integer timeWindowDays,
        int maxReleases,
        boolean includePrereleases) {

    public GitHubRepositoryReleaseQuery {
        requireText(projectId, "projectId");
        requireText(displayName, "displayName");
        requireText(repositoryOwner, "repositoryOwner");
        requireText(repositoryName, "repositoryName");
        if (timeWindowDays != null && (timeWindowDays < 1 || timeWindowDays > 365)) {
            throw new IllegalArgumentException("timeWindowDays must be between 1 and 365");
        }
        if (maxReleases < 1 || maxReleases > 30) {
            throw new IllegalArgumentException("maxReleases must be between 1 and 30");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
