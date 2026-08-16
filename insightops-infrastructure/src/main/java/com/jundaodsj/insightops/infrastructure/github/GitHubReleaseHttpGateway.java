package com.jundaodsj.insightops.infrastructure.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.infrastructure.config.GitHubToolProperties;
import com.jundaodsj.insightops.project.application.P0TrackedProjectCatalog;
import com.jundaodsj.insightops.project.application.P0TrackedProjectCatalog.ProjectDefinition;
import com.jundaodsj.insightops.tool.application.github.GitHubRelease;
import com.jundaodsj.insightops.tool.application.github.GitHubReleaseGateway;
import com.jundaodsj.insightops.tool.application.github.GitHubReleaseQuery;
import com.jundaodsj.insightops.tool.application.github.GitHubReleaseResult;
import com.jundaodsj.insightops.tool.application.github.GitHubToolErrorCode;
import com.jundaodsj.insightops.tool.application.github.GitHubToolException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class GitHubReleaseHttpGateway implements GitHubReleaseGateway {

    private final GitHubToolProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public GitHubReleaseHttpGateway(
            GitHubToolProperties properties,
            ObjectMapper objectMapper) {
        this(
                properties,
                objectMapper,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds()))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build());
    }

    GitHubReleaseHttpGateway(
            GitHubToolProperties properties,
            ObjectMapper objectMapper,
            HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public GitHubReleaseResult listReleases(GitHubReleaseQuery query) {
        Instant fetchedAt = Instant.now();
        Instant cutoff = query.timeWindowDays() == null
                ? null
                : fetchedAt.minus(Duration.ofDays(query.timeWindowDays()));
        List<GitHubRelease> releases = new ArrayList<>();
        for (String projectId : query.projectIds()) {
            ProjectDefinition repository = P0TrackedProjectCatalog.find(projectId)
                    .orElseThrow(() -> new GitHubToolException(
                            GitHubToolErrorCode.VALIDATION_ERROR,
                            new IllegalArgumentException("Project is not in the P0 allowlist")));
            releases.addAll(fetchProject(
                    projectId,
                    repository,
                    cutoff,
                    query.maxReleasesPerProject(),
                    query.includePrereleases()));
        }
        return new GitHubReleaseResult(releases, fetchedAt);
    }

    private List<GitHubRelease> fetchProject(
            String projectId,
            ProjectDefinition repository,
            Instant cutoff,
            int maxReleases,
            boolean includePrereleases) {
        URI uri = URI.create(properties.baseUrl()
                + "/repos/" + repository.repositoryOwner() + "/" + repository.repositoryName()
                + "/releases?per_page=30&page=1");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(properties.requestTimeoutSeconds()))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", properties.apiVersion())
                .header("User-Agent", "InsightOps-Agent/0.1")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString());
            validateStatus(response.statusCode());
            return parseReleases(
                    projectId,
                    repository,
                    response.body(),
                    cutoff,
                    maxReleases,
                    includePrereleases);
        }
        catch (HttpTimeoutException exception) {
            throw new GitHubToolException(GitHubToolErrorCode.TIMEOUT, exception);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GitHubToolException(GitHubToolErrorCode.TIMEOUT, exception);
        }
        catch (IOException exception) {
            throw new GitHubToolException(GitHubToolErrorCode.TRANSIENT_REMOTE, exception);
        }
    }

    private List<GitHubRelease> parseReleases(
            String projectId,
            ProjectDefinition repository,
            String responseBody,
            Instant cutoff,
            int maxReleases,
            boolean includePrereleases) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (!root.isArray()) {
                throw new IOException("GitHub Releases response is not an array");
            }
            List<GitHubRelease> releases = new ArrayList<>();
            for (JsonNode item : root) {
                if (item.path("draft").asBoolean(false)) {
                    continue;
                }
                boolean prerelease = item.path("prerelease").asBoolean(false);
                if (prerelease && !includePrereleases) {
                    continue;
                }
                Instant publishedAt = timestamp(item, "published_at", "created_at");
                if (publishedAt == null || cutoff != null && publishedAt.isBefore(cutoff)) {
                    continue;
                }
                String tagName = item.path("tag_name").asText("");
                String releaseName = item.path("name").asText(tagName);
                releases.add(new GitHubRelease(
                        projectId,
                        repository.displayName(),
                        tagName,
                        releaseName.isBlank() ? tagName : releaseName,
                        publishedAt,
                        item.path("html_url").asText(),
                        prerelease,
                        excerpt(item.path("body").asText(""))));
                if (releases.size() >= maxReleases) {
                    break;
                }
            }
            return releases;
        }
        catch (IOException | RuntimeException exception) {
            if (exception instanceof GitHubToolException toolException) {
                throw toolException;
            }
            throw new GitHubToolException(GitHubToolErrorCode.INTERNAL_ERROR, exception);
        }
    }

    private void validateStatus(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            return;
        }
        GitHubToolErrorCode code = switch (statusCode) {
            case 403, 429 -> GitHubToolErrorCode.RATE_LIMITED;
            case 404, 422 -> GitHubToolErrorCode.VALIDATION_ERROR;
            default -> GitHubToolErrorCode.TRANSIENT_REMOTE;
        };
        throw new GitHubToolException(code, new IllegalStateException("GitHub HTTP " + statusCode));
    }

    private Instant timestamp(JsonNode item, String primary, String fallback) {
        String value = item.path(primary).asText("");
        if (value.isBlank()) {
            value = item.path(fallback).asText("");
        }
        return value.isBlank() ? null : Instant.parse(value);
    }

    private String excerpt(String body) {
        String normalized = body.replace("\u0000", "").trim();
        return normalized.length() <= properties.maxBodyChars()
                ? normalized
                : normalized.substring(0, properties.maxBodyChars()) + "…";
    }
}
