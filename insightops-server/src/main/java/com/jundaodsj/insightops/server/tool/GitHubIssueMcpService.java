package com.jundaodsj.insightops.server.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/** A narrowly scoped adapter: no arbitrary endpoint, tool name or operation from the model. */
@Service
public class GitHubIssueMcpService {
    private static final int MAX_RESULT_CHARS = 512_000;
    private static final String OFFICIAL_ORIGIN = "https://api.githubcopilot.com";
    private final GitHubMcpProperties properties;
    private final ObjectMapper mapper;
    private final SessionFactory sessionFactory;
    private volatile Session session;

    @Autowired
    public GitHubIssueMcpService(GitHubMcpProperties properties, ObjectMapper mapper) {
        this(properties, mapper, () -> connect(properties, OFFICIAL_ORIGIN));
    }

    GitHubIssueMcpService(GitHubMcpProperties properties, ObjectMapper mapper, SessionFactory factory) {
        this.properties = properties;
        this.mapper = mapper;
        this.sessionFactory = factory;
    }

    public Issue readIssue(String owner, String repo, int issueNumber) {
        if (!properties.isEnabled()) throw failure("MCP_GITHUB_NOT_CONFIGURED");
        if (properties.getToken().isBlank()) throw failure("MCP_GITHUB_AUTH_REQUIRED");
        if (!validSegment(owner) || !validSegment(repo) || issueNumber < 1) {
            throw failure("MCP_GITHUB_INVALID_ARGUMENTS");
        }
        Session activeSession = null;
        try {
            activeSession = session();
            McpSchema.CallToolResult result = activeSession.call(Map.of(
                    "method", "get", "owner", owner, "repo", repo, "issue_number", issueNumber));
            if (result == null) throw failure("MCP_GITHUB_INVALID_RESPONSE");
            if (Boolean.TRUE.equals(result.isError())) throw failure(errorCode(errorText(result)));
            JsonNode issue = issueData(result);
            if (issue == null || !issue.isObject()) throw failure("MCP_GITHUB_INVALID_RESPONSE");
            if (issue.has("issue")) {
                if (!"get".equals(issue.path("method").asText())) {
                    throw failure("MCP_GITHUB_INVALID_RESPONSE");
                }
                issue = issue.path("issue");
            }
            return normalize(issue, owner, repo, issueNumber);
        } catch (GitHubMcpException exception) {
            if (exception.code().equals("MCP_GITHUB_UNAVAILABLE") || exception.code().equals("TOOL_TIMEOUT")) {
                discard(activeSession);
            }
            throw exception;
        } catch (RuntimeException exception) {
            discard(activeSession);
            // SDK/remote errors may contain request details; never retain them as causes.
            throw safeFailure(exception);
        }
    }

    private Session session() {
        Session current = session;
        if (current == null) {
            synchronized (this) {
                current = session;
                if (current == null) session = current = sessionFactory.connect();
            }
        }
        return current;
    }

    private void discard(Session failed) {
        if (failed == null) return;
        synchronized (this) {
            if (session != failed) return;
            session = null;
        }
        try { failed.close(); } catch (RuntimeException ignored) { /* Keep the original safe error. */ }
    }

    // Package-private origin override is only used by an in-process protocol fixture in tests.
    static Session connect(GitHubMcpProperties properties, String origin) {
        Duration timeout = Duration.ofSeconds(Math.max(1, Math.min(25, properties.getRequestTimeoutSeconds())));
        var transport = HttpClientStreamableHttpTransport.builder(origin)
                .endpoint("/mcp/")
                .clientBuilder(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER))
                .connectTimeout(Duration.ofSeconds(Math.min(5, timeout.toSeconds())))
                .requestBuilder(HttpRequest.newBuilder().timeout(timeout))
                .httpRequestCustomizer((builder, method, uri, body, context) -> builder
                        .setHeader("Authorization", "Bearer " + properties.getToken())
                        .setHeader("X-MCP-Readonly", "true")
                        .setHeader("X-MCP-Tools", "issue_read"))
                .openConnectionOnStartup(false)
                .resumableStreams(false)
                .build();
        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("InsightOps-GitHub-Issue", "1.0"))
                .requestTimeout(timeout)
                .initializationTimeout(timeout)
                .enableCallToolSchemaCaching(false)
                .build();
        try {
            client.initialize();
            if (client.listTools().tools().stream().noneMatch(tool -> "issue_read".equals(tool.name()))) {
                throw failure("MCP_GITHUB_TOOL_ERROR");
            }
            return new Session() {
                public McpSchema.CallToolResult call(Map<String, Object> arguments) {
                    return client.callTool(new McpSchema.CallToolRequest("issue_read", arguments));
                }
                public void close() { client.close(); }
            };
        } catch (RuntimeException exception) {
            client.close();
            throw safeFailure(exception);
        }
    }

    private JsonNode issueData(McpSchema.CallToolResult result) {
        try {
            if (result.structuredContent() != null) {
                String json = mapper.writeValueAsString(result.structuredContent());
                if (json.length() > MAX_RESULT_CHARS) throw failure("MCP_GITHUB_INVALID_RESPONSE");
                return mapper.readTree(json);
            }
            StringBuilder text = new StringBuilder();
            if (result.content() != null) {
                for (McpSchema.Content content : result.content()) {
                    if (content instanceof McpSchema.TextContent item && item.text() != null) {
                        if (text.length() + item.text().length() > MAX_RESULT_CHARS) {
                            throw failure("MCP_GITHUB_INVALID_RESPONSE");
                        }
                        text.append(item.text());
                    }
                }
            }
            if (text.isEmpty()) throw failure("MCP_GITHUB_INVALID_RESPONSE");
            return mapper.readTree(text.toString());
        } catch (GitHubMcpException exception) {
            throw exception;
        } catch (Exception exception) {
            throw failure("MCP_GITHUB_INVALID_RESPONSE");
        }
    }

    private Issue normalize(JsonNode data, String owner, String repo, int requestedNumber) {
        if (data == null || !data.isObject() || !data.path("number").isIntegralNumber()
                || !data.path("number").canConvertToInt()
                || data.path("number").asInt() != requestedNumber || data.hasNonNull("pull_request")) {
            throw failure("MCP_GITHUB_INVALID_RESPONSE");
        }
        String title = field(data, "title");
        String url = field(data, "html_url");
        if (url.isBlank()) url = field(data, "url");
        if (title.isBlank() || !validIssueUrl(url, owner, repo, requestedNumber)) {
            throw failure("MCP_GITHUB_INVALID_RESPONSE");
        }
        String state = field(data, "state").toLowerCase(Locale.ROOT);
        if (!state.equals("open") && !state.equals("closed")) throw failure("MCP_GITHUB_INVALID_RESPONSE");
        String body = field(data, "body").replace("\u0000", "");
        int limit = Math.max(1, Math.min(32_000, properties.getMaxBodyChars()));
        boolean truncated = body.length() > limit;
        String author = field(data.path("user"), "login");
        if (author.isBlank()) author = field(data.path("author"), "login");
        if (author.isBlank() && data.path("author").isTextual()) author = data.path("author").asText();
        return new Issue(requestedNumber, truncate(title, 500), url, state, truncate(body, limit),
                truncate(author, 100), truncate(fieldOrAlias(data, "created_at", "createdAt"), 50),
                truncate(fieldOrAlias(data, "updated_at", "updatedAt"), 50), truncated, Instant.now());
    }

    private static boolean validIssueUrl(String value, String owner, String repo, int number) {
        try {
            URI url = URI.create(value);
            return "https".equals(url.getScheme()) && "github.com".equalsIgnoreCase(url.getHost())
                    && url.getPort() == -1 && url.getUserInfo() == null
                    && url.getQuery() == null && url.getFragment() == null
                    && ("/" + owner + "/" + repo + "/issues/" + number).equalsIgnoreCase(url.getPath());
        } catch (IllegalArgumentException exception) { return false; }
    }

    private static boolean validSegment(String segment) {
        return segment != null && segment.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    }

    private static String field(JsonNode node, String name) {
        JsonNode value = node.path(name);
        return value.isTextual() ? value.asText() : "";
    }

    private static String fieldOrAlias(JsonNode node, String name, String alias) {
        String value = field(node, name);
        return value.isBlank() ? field(node, alias) : value;
    }

    private static String truncate(String text, int limit) {
        int end = Math.min(text.length(), limit);
        if (end < text.length() && end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }

    private static String errorText(McpSchema.CallToolResult result) {
        StringBuilder text = new StringBuilder();
        if (result.content() != null) {
            for (var content : result.content()) {
                if (content instanceof McpSchema.TextContent item && item.text() != null && text.length() < 4_096) {
                    text.append(truncate(item.text(), 4_096 - text.length()));
                }
            }
        }
        return text.toString();
    }

    private static String errorCode(String raw) {
        String message = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        if (message.contains("rate limit") || message.contains("429")) return "TOOL_RATE_LIMITED";
        if (message.contains("401") || message.contains("403") || message.contains("bad credentials")
                || message.contains("unauthorized") || message.contains("forbidden")) return "MCP_GITHUB_AUTH_FAILED";
        if (message.contains("404") || message.contains("not found") || message.contains("could not resolve")) {
            return "MCP_GITHUB_NOT_FOUND";
        }
        return "MCP_GITHUB_TOOL_ERROR";
    }

    private static GitHubMcpException safeFailure(Throwable exception) {
        for (int depth = 0; exception != null && depth < 12; depth++, exception = exception.getCause()) {
            if (exception instanceof GitHubMcpException known) return known;
            if (exception instanceof TimeoutException || exception instanceof HttpTimeoutException) return failure("TOOL_TIMEOUT");
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return failure("TOOL_CANCELLED");
            }
            if (exception instanceof McpHttpClientTransportAuthorizationException) return failure("MCP_GITHUB_AUTH_FAILED");
            String code = errorCode(exception.getMessage());
            if (!code.equals("MCP_GITHUB_TOOL_ERROR")) return failure(code);
        }
        return failure("MCP_GITHUB_UNAVAILABLE");
    }

    private static GitHubMcpException failure(String code) { return new GitHubMcpException(code); }

    @PreDestroy
    public void close() {
        Session current;
        synchronized (this) { current = session; session = null; }
        if (current != null) current.close();
    }

    @FunctionalInterface
    interface SessionFactory { Session connect(); }
    interface Session extends AutoCloseable {
        McpSchema.CallToolResult call(Map<String, Object> arguments);
        @Override void close();
    }

    public record Issue(int number, String title, String url, String state, String body, String author,
                        String createdAt, String updatedAt, boolean bodyTruncated, Instant fetchedAt) { }

    public static class GitHubMcpException extends RuntimeException {
        private final String code;
        public GitHubMcpException(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
}
