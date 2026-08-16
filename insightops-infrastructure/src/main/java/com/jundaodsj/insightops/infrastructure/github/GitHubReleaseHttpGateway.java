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

    private static final int PAGE_SIZE = 30;
    private static final int MAX_PAGES = 10;

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
        boolean truncated = false;
        for (String projectId : query.projectIds()) {
            ProjectDefinition repository = P0TrackedProjectCatalog.find(projectId)
                    .orElseThrow(() -> new GitHubToolException(
                            GitHubToolErrorCode.VALIDATION_ERROR,
                            new IllegalArgumentException("Project is not in the P0 allowlist")));
            ProjectFetchResult projectResult = fetchProject(
                    projectId,
                    repository,
                    cutoff,
                    query.maxReleasesPerProject(),
                    query.includePrereleases());
            releases.addAll(projectResult.releases());
            truncated = truncated || projectResult.truncated();
        }
        return new GitHubReleaseResult(releases, fetchedAt, truncated);
    }

    private ProjectFetchResult fetchProject(
            String projectId,
            ProjectDefinition repository,
            Instant cutoff,
            int maxReleases,
            boolean includePrereleases) {
        List<GitHubRelease> releases = new ArrayList<>();
        try {
            for (int page = 1; page <= MAX_PAGES; page++) {
                URI uri = URI.create(properties.baseUrl()
                        + "/repos/" + repository.repositoryOwner() + "/" + repository.repositoryName()
                        + "/releases?per_page=" + PAGE_SIZE + "&page=" + page);
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(properties.requestTimeoutSeconds()))
                        .header("Accept", "application/vnd.github+json")
                        .header("X-GitHub-Api-Version", properties.apiVersion())
                        .header("User-Agent", "InsightOps-Agent/0.1")
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofString());
                validateStatus(response.statusCode());
                PageResult parsed = parsePage(
                        projectId,
                        repository,
                        response.body(),
                        cutoff,
                        includePrereleases);
                int remaining = maxReleases - releases.size();
                if (parsed.releases().size() > remaining) {
                    releases.addAll(parsed.releases().subList(0, remaining));
                    return new ProjectFetchResult(releases, true);
                }
                releases.addAll(parsed.releases());
                if (releases.size() == maxReleases) {
                    boolean moreResultsPossible = !parsed.reachedCutoff()
                            && parsed.rawCount() == PAGE_SIZE;
                    return new ProjectFetchResult(releases, moreResultsPossible);
                }
                if (parsed.reachedCutoff() || parsed.rawCount() < PAGE_SIZE) {
                    return new ProjectFetchResult(releases, false);
                }
            }
            return new ProjectFetchResult(releases, true);
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

    private PageResult parsePage(
            String projectId,
            ProjectDefinition repository,
            String responseBody,
            Instant cutoff,
            boolean includePrereleases) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (!root.isArray()) {
                throw new IOException("GitHub Releases response is not an array");
            }
            List<GitHubRelease> releases = new ArrayList<>();
            boolean reachedCutoff = false;
            for (JsonNode item : root) {
                if (item.path("draft").asBoolean(false)) {
                    continue;
                }
                Instant publishedAt = timestamp(item, "published_at", "created_at");
                if (publishedAt == null) {
                    continue;
                }
                if (cutoff != null && publishedAt.isBefore(cutoff)) {
                    reachedCutoff = true;
                    break;
                }
                boolean prerelease = item.path("prerelease").asBoolean(false);
                if (prerelease && !includePrereleases) {
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
            }
            return new PageResult(root.size(), releases, reachedCutoff);
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

    private record PageResult(int rawCount, List<GitHubRelease> releases, boolean reachedCutoff) {
    }

    private record ProjectFetchResult(List<GitHubRelease> releases, boolean truncated) {
    }
}
