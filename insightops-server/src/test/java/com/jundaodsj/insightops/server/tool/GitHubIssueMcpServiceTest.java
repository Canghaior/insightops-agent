package com.jundaodsj.insightops.server.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubIssueMcpServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldNotConnectWhenDisabledOrMissingToken() {
        GitHubMcpProperties properties = new GitHubMcpProperties();
        AtomicInteger connections = new AtomicInteger();
        var service = new GitHubIssueMcpService(properties, mapper, () -> {
            connections.incrementAndGet();
            throw new AssertionError("must not connect");
        });
        assertThatThrownBy(() -> service.readIssue("spring-projects", "spring-ai", 42))
                .hasMessage("MCP_GITHUB_NOT_CONFIGURED");
        properties.setEnabled(true);
        assertThatThrownBy(() -> service.readIssue("spring-projects", "spring-ai", 42))
                .hasMessage("MCP_GITHUB_AUTH_REQUIRED");
        assertThat(connections).hasValue(0);
    }

    @Test
    void shouldReuseSessionAndRestrictCallToSingleIssueGet() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        AtomicReference<Map<String, Object>> arguments = new AtomicReference<>();
        var properties = configured();
        properties.setMaxBodyChars(8);
        var result = textResult(mapper.writeValueAsString(issueData()), false);
        var service = new GitHubIssueMcpService(properties, mapper, () -> {
            connections.incrementAndGet();
            return new GitHubIssueMcpService.Session() {
                public McpSchema.CallToolResult call(Map<String, Object> input) {
                    arguments.set(input);
                    return result;
                }
                public void close() { closed.incrementAndGet(); }
            };
        });
        var issue = service.readIssue("spring-projects", "spring-ai", 42);
        service.readIssue("spring-projects", "spring-ai", 42);
        assertThat(arguments.get()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "method", "get", "owner", "spring-projects", "repo", "spring-ai", "issue_number", 42));
        assertThat(issue.number()).isEqualTo(42);
        assertThat(issue.title()).isEqualTo("ChatClient issue");
        assertThat(issue.state()).isEqualTo("open");
        assertThat(issue.url()).isEqualTo("https://github.com/spring-projects/spring-ai/issues/42");
        assertThat(issue.author()).isEqualTo("contributor");
        assertThat(issue.body()).isEqualTo("A long i");
        assertThat(issue.bodyTruncated()).isTrue();
        assertThat(issue.fetchedAt()).isNotNull();
        assertThat(connections).hasValue(1);
        service.close();
        assertThat(closed).hasValue(1);
    }

    @Test
    void shouldAcceptOfficialStructuredIssueReadEnvelope() {
        var result = new McpSchema.CallToolResult(List.of(), false,
                Map.of("method", "get", "issue", issueData()), null);
        var issue = service(result).readIssue("spring-projects", "spring-ai", 42);
        assertThat(issue.body()).isEqualTo("A long issue body for testing.");
        assertThat(issue.createdAt()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(issue.bodyTruncated()).isFalse();
    }

    @Test
    void shouldRejectWrongRepositoryIssueNumberPullRequestAndUnexpectedShape() throws Exception {
        for (Map<String, Object> invalid : List.of(
                changed("html_url", "https://github.com/other/repo/issues/42"),
                changed("html_url", "https://github.com/spring-projects/spring-ai/pull/42"),
                changed("html_url", "https://github.com/spring-projects/spring-ai/issues/42?token=secret"),
                changed("number", 43),
                changed("number", 42.5),
                changed("pull_request", Map.of("url", "https://example.com")),
                changed("title", ""),
                changed("state", "unknown"))) {
            var service = service(textResult(mapper.writeValueAsString(invalid), false));
            assertThatThrownBy(() -> service.readIssue("spring-projects", "spring-ai", 42))
                    .hasMessage("MCP_GITHUB_INVALID_RESPONSE");
        }
        assertThatThrownBy(() -> service(textResult("not-json", false))
                .readIssue("spring-projects", "spring-ai", 42)).hasMessage("MCP_GITHUB_INVALID_RESPONSE");
        assertThatThrownBy(() -> service(textResult("x".repeat(512_001), false))
                .readIssue("spring-projects", "spring-ai", 42)).hasMessage("MCP_GITHUB_INVALID_RESPONSE");
        assertThatThrownBy(() -> service(textResult("null", false))
                .readIssue("spring-projects", "spring-ai", 42)).hasMessage("MCP_GITHUB_INVALID_RESPONSE");
    }

    @Test
    void shouldNotTreatToolErrorsAsEvidenceOrExposeRemoteErrorContents() {
        Map<String, String> failures = Map.of(
                "failed to get issue: 404 Not Found remote-secret", "MCP_GITHUB_NOT_FOUND",
                "401 Bad credentials remote-secret", "MCP_GITHUB_AUTH_FAILED",
                "403 API rate limit exceeded remote-secret", "TOOL_RATE_LIMITED",
                "unclassified remote-secret", "MCP_GITHUB_TOOL_ERROR");
        failures.forEach((message, code) -> assertThatThrownBy(() -> service(textResult(message, true))
                .readIssue("spring-projects", "spring-ai", 42))
                .isInstanceOf(GitHubIssueMcpService.GitHubMcpException.class)
                .hasMessage(code).hasNoCause());
    }

    @Test
    void shouldSanitizeSdkExceptionsAndValidateInputsBeforeConnecting() {
        AtomicInteger connections = new AtomicInteger();
        var service = new GitHubIssueMcpService(configured(), mapper, () -> {
            connections.incrementAndGet();
            throw new IllegalStateException("network failed, Authorization: Bearer test-secret");
        });
        assertThatThrownBy(() -> service.readIssue("../other", "spring-ai", 42))
                .hasMessage("MCP_GITHUB_INVALID_ARGUMENTS");
        assertThatThrownBy(() -> service.readIssue("spring-projects", "spring-ai", 0))
                .hasMessage("MCP_GITHUB_INVALID_ARGUMENTS");
        assertThat(connections).hasValue(0);
        assertThatThrownBy(() -> service.readIssue("spring-projects", "spring-ai", 42))
                .hasMessage("MCP_GITHUB_UNAVAILABLE").hasNoCause();
    }

    @Test
    void shouldReconnectAfterTransportFailure() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        var result = textResult(mapper.writeValueAsString(issueData()), false);
        var service = new GitHubIssueMcpService(configured(), mapper, () -> {
            boolean fail = connections.incrementAndGet() == 1;
            return new GitHubIssueMcpService.Session() {
                public McpSchema.CallToolResult call(Map<String, Object> args) {
                    if (fail) throw new IllegalStateException("connection lost");
                    return result;
                }
                public void close() { closes.incrementAndGet(); }
            };
        });
        assertThatThrownBy(() -> service.readIssue("spring-projects", "spring-ai", 42))
                .hasMessage("MCP_GITHUB_UNAVAILABLE");
        assertThat(service.readIssue("spring-projects", "spring-ai", 42).number()).isEqualTo(42);
        assertThat(connections).hasValue(2);
        assertThat(closes).hasValue(1);
        service.close();
    }

    private GitHubIssueMcpService service(McpSchema.CallToolResult result) {
        return new GitHubIssueMcpService(configured(), mapper, () -> new GitHubIssueMcpService.Session() {
            public McpSchema.CallToolResult call(Map<String, Object> args) { return result; }
            public void close() { }
        });
    }

    static GitHubMcpProperties configured() {
        var properties = new GitHubMcpProperties();
        properties.setEnabled(true);
        properties.setToken("test-secret");
        properties.setRequestTimeoutSeconds(3);
        return properties;
    }

    static Map<String, Object> issueData() {
        return Map.of("number", 42, "title", "ChatClient issue", "state", "open",
                "html_url", "https://github.com/spring-projects/spring-ai/issues/42",
                "body", "A long issue body for testing.", "user", Map.of("login", "contributor"),
                "created_at", "2026-01-01T00:00:00Z", "updated_at", "2026-01-02T00:00:00Z");
    }

    private Map<String, Object> changed(String key, Object value) {
        var data = new java.util.HashMap<>(issueData());
        data.put(key, value);
        return data;
    }

    static McpSchema.CallToolResult textResult(String text, boolean error) {
        return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(text)), error, null, null);
    }
}
