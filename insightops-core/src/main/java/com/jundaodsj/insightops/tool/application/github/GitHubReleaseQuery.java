package com.jundaodsj.insightops.tool.application.github;

import java.util.List;

public record GitHubReleaseQuery(
        List<String> projectIds,
        Integer timeWindowDays,
        int maxReleasesPerProject,
        boolean includePrereleases) {

    public GitHubReleaseQuery {
        projectIds = projectIds == null ? List.of() : projectIds.stream().distinct().toList();
        if (projectIds.isEmpty() || projectIds.size() > 3) {
            throw new IllegalArgumentException("projectIds must contain between 1 and 3 projects");
        }
        if (timeWindowDays != null && (timeWindowDays < 1 || timeWindowDays > 365)) {
            throw new IllegalArgumentException("timeWindowDays must be between 1 and 365");
        }
        if (maxReleasesPerProject < 1 || maxReleasesPerProject > 30) {
            throw new IllegalArgumentException("maxReleasesPerProject must be between 1 and 30");
        }
    }
}
