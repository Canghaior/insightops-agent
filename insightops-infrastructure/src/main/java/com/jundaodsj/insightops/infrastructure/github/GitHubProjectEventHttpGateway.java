package com.jundaodsj.insightops.infrastructure.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.infrastructure.config.GitHubToolProperties;
import com.jundaodsj.insightops.tool.application.github.GitHubProjectEvent;
import com.jundaodsj.insightops.tool.application.github.GitHubProjectEventGateway;
import com.jundaodsj.insightops.tool.application.github.GitHubToolErrorCode;
import com.jundaodsj.insightops.tool.application.github.GitHubToolException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.Locale;

@Component
public class GitHubProjectEventHttpGateway implements GitHubProjectEventGateway {

    private final GitHubToolProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String token;

    @Autowired
    public GitHubProjectEventHttpGateway(
            GitHubToolProperties properties,
            ObjectMapper objectMapper,
            @Value("${insightops.tool.github.token:}") String token) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), token);
    }

    GitHubProjectEventHttpGateway(
            GitHubToolProperties properties, ObjectMapper objectMapper,
            HttpClient httpClient, String token) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.token = token == null ? "" : token.trim();
    }

    @Override
    public FetchResult fetch(String repositoryOwner, String repositoryName, int maxPerSource) {
        Instant fetchedAt = Instant.now();
        int safeLimit = Math.max(1, Math.min(maxPerSource, 100));
        List<GitHubProjectEvent> events = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        JsonNode issues = requestArray(path(repositoryOwner, repositoryName,
                "/issues?state=all&sort=updated&direction=desc&per_page=" + safeLimit), false);
        for (JsonNode item : issues) {
            events.add(issueOrPullRequest(item));
        }
        JsonNode advisories = requestArray(path(repositoryOwner, repositoryName,
                "/security-advisories?per_page=" + safeLimit), true);
        if (advisories == null) {
            unavailable.add("GITHUB_SECURITY_ADVISORY");
        } else {
            for (JsonNode item : advisories) {
                events.add(securityAdvisory(item));
            }
        }
        return new FetchResult(events, unavailable, fetchedAt);
    }

    private GitHubProjectEvent issueOrPullRequest(JsonNode item) {
        boolean pullRequest = item.hasNonNull("pull_request");
        String eventType = pullRequest ? "GITHUB_PULL_REQUEST" : "GITHUB_ISSUE";
        String state = item.path("state").asText("open").toUpperCase(Locale.ROOT);
        String body = excerpt(item.path("body").asText(""));
        String summary = body.isBlank()
                ? item.path("title").asText("GitHub event")
                : body;
        List<String> labels = new ArrayList<>();
        for (JsonNode label : item.path("labels")) {
            String value = label.path("name").asText("").trim();
            if (!value.isBlank()) labels.add(value);
        }
        int importance = importance(labels, pullRequest, false);
        return new GitHubProjectEvent(
                (pullRequest ? "pr:" : "issue:") + item.path("number").asText(),
                eventType,
                item.path("title").asText("GitHub event"),
                summary,
                item.path("html_url").asText(),
                state,
                item.path("user").path("login").asText(null),
                labels,
                null,
                importance,
                instant(item, "created_at", "updated_at"),
                instant(item, "updated_at", "created_at"),
                item.toString());
    }

    private GitHubProjectEvent securityAdvisory(JsonNode item) {
        String severity = item.path("severity").asText("unknown").toUpperCase(Locale.ROOT);
        String ghsaId = item.path("ghsa_id").asText(item.path("cve_id").asText("advisory"));
        String description = excerpt(item.path("description").asText(""));
        return new GitHubProjectEvent(
                "advisory:" + ghsaId,
                "GITHUB_SECURITY_ADVISORY",
                item.path("summary").asText(ghsaId),
                description.isBlank() ? item.path("summary").asText(ghsaId) : description,
                item.path("html_url").asText(),
                item.path("state").asText("published").toUpperCase(Locale.ROOT),
                item.path("publisher").path("login").asText(null),
                List.of(severity.toLowerCase(Locale.ROOT), ghsaId),
                severity,
                switch (severity) {
                    case "CRITICAL" -> 5;
                    case "HIGH" -> 4;
                    case "MODERATE", "MEDIUM" -> 3;
                    default -> 2;
                },
                instant(item, "published_at", "updated_at"),
                instant(item, "updated_at", "published_at"),
                item.toString());
    }

    private JsonNode requestArray(String relativePath, boolean optional) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(properties.baseUrl() + relativePath))
                    .timeout(Duration.ofSeconds(properties.requestTimeoutSeconds()))
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", properties.apiVersion())
                    .header("User-Agent", "InsightOps-Agent/0.1")
                    .GET();
            if (!token.isBlank()) builder.header("Authorization", "Bearer " + token);
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (optional && (response.statusCode() == 403 || response.statusCode() == 404)) return null;
            validateStatus(response.statusCode());
            JsonNode root = objectMapper.readTree(response.body());
            if (!root.isArray()) throw new IOException("GitHub response is not an array");
            return root;
        } catch (HttpTimeoutException exception) {
            throw new GitHubToolException(GitHubToolErrorCode.TIMEOUT, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GitHubToolException(GitHubToolErrorCode.TIMEOUT, exception);
        } catch (IOException exception) {
            throw new GitHubToolException(GitHubToolErrorCode.TRANSIENT_REMOTE, exception);
        }
    }

    private String path(String owner, String repository, String suffix) {
        return "/repos/" + owner + "/" + repository + suffix;
    }

    private void validateStatus(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) return;
        GitHubToolErrorCode code = switch (statusCode) {
            case 403, 429 -> GitHubToolErrorCode.RATE_LIMITED;
            case 404, 422 -> GitHubToolErrorCode.VALIDATION_ERROR;
            default -> GitHubToolErrorCode.TRANSIENT_REMOTE;
        };
        throw new GitHubToolException(code, new IllegalStateException("GitHub HTTP " + statusCode));
    }

    private int importance(List<String> labels, boolean pullRequest, boolean advisory) {
        String joined = String.join(" ", labels).toLowerCase(Locale.ROOT);
        if (advisory || joined.contains("security") || joined.contains("critical")) return 5;
        if (joined.contains("breaking") || joined.contains("regression") || joined.contains("bug")) return 4;
        return pullRequest ? 2 : 3;
    }

    private Instant instant(JsonNode item, String primary, String fallback) {
        String value = item.path(primary).asText("");
        if (value.isBlank()) value = item.path(fallback).asText("");
        return value.isBlank() ? Instant.now() : Instant.parse(value);
    }

    private String excerpt(String value) {
        String normalized = value == null ? "" : value.replace("\u0000", "").trim();
        return normalized.length() <= properties.maxBodyChars()
                ? normalized
                : normalized.substring(0, properties.maxBodyChars()) + "…";
    }
}
