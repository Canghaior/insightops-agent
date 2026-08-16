package com.jundaodsj.insightops.tool.application.github;

import java.time.Instant;

public record GitHubRelease(
        String projectId,
        String projectName,
        String tagName,
        String releaseName,
        Instant publishedAt,
        String url,
        boolean prerelease,
        String notesExcerpt) {
}
