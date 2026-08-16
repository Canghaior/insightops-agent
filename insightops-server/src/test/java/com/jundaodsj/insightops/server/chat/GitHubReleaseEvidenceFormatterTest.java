package com.jundaodsj.insightops.server.chat;

import com.jundaodsj.insightops.tool.application.github.GitHubRelease;
import com.jundaodsj.insightops.tool.application.github.GitHubReleaseQuery;
import com.jundaodsj.insightops.tool.application.github.GitHubReleaseResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubReleaseEvidenceFormatterTest {

    @Test
    void shouldKeepOfficialReleaseSourceButRemoveLinksFromUntrustedNotes() {
        String officialRelease = "https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0";
        GitHubRelease release = new GitHubRelease(
                "spring-ai",
                "Spring AI",
                "v2.0.0",
                "Spring AI 2.0.0",
                Instant.parse("2026-06-12T15:14:59Z"),
                officialRelease,
                false,
                "Read [upgrade notes](https://docs.example.com/upgrade) or https://evil.example/prompt");

        String evidence = new GitHubReleaseEvidenceFormatter().format(
                new GitHubReleaseQuery(List.of("spring-ai"), null, 2, false),
                new GitHubReleaseResult(List.of(release), Instant.parse("2026-08-17T00:00:00Z")));

        assertThat(evidence)
                .contains(
                        officialRelease,
                        "spring-ai=1",
                        "upgrade notes [external link omitted]")
                .doesNotContain("https://docs.example.com", "https://evil.example");
    }
}
