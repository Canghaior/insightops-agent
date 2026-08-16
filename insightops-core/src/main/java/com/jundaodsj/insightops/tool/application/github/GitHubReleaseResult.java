package com.jundaodsj.insightops.tool.application.github;

import java.time.Instant;
import java.util.List;

public record GitHubReleaseResult(
        List<GitHubRelease> releases,
        Instant fetchedAt) {

    public GitHubReleaseResult {
        releases = releases == null ? List.of() : List.copyOf(releases);
        if (fetchedAt == null) {
            throw new IllegalArgumentException("fetchedAt must not be null");
        }
    }

    public List<String> sourceUrls() {
        return releases.stream().map(GitHubRelease::url).distinct().toList();
    }
}
