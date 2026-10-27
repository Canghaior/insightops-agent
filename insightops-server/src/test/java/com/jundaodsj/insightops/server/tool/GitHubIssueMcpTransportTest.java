package com.jundaodsj.insightops.server.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs the real SDK against a local MCP fixture, without a GitHub account or external network. */
class GitHubIssueMcpTransportTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldAuthenticateDiscoverOnceAndReadViaRealSdkWithJsonAndSseResults() throws Exception {
        AtomicInteger initializations = new AtomicInteger();
        AtomicInteger discoveries = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        List<JsonNode> arguments = new CopyOnWriteArrayList<>();
        List<String> authHeaders = new CopyOnWriteArrayList<>();
        List<String> readOnlyHeaders = new CopyOnWriteArrayList<>();
        List<String> toolHeaders = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp/", exchange -> {
            try {
                if (!exchange.getRequestMethod().equals("POST")) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
                readOnlyHeaders.add(exchange.getRequestHeaders().getFirst("X-MCP-Readonly"));
                toolHeaders.add(exchange.getRequestHeaders().getFirst("X-MCP-Tools"));
                JsonNode request = mapper.readTree(exchange.getRequestBody());
                String method = request.path("method").asText();
                Object result;
                switch (method) {
                    case "initialize" -> {
                        initializations.incrementAndGet();
                        result = Map.of("protocolVersion", request.path("params").path("protocolVersion").asText(),
                                "capabilities", Map.of("tools", Map.of("listChanged", false)),
                                "serverInfo", Map.of("name", "fixture", "version", "1"));
                    }
                    case "tools/list" -> {
                        discoveries.incrementAndGet();
                        result = Map.of("tools", List.of(Map.of("name", "issue_read",
                                "description", "Read an issue", "inputSchema", Map.of("type", "object"))));
                    }
                    case "tools/call" -> {
                        calls.incrementAndGet();
                        arguments.add(request.path("params"));
                        result = Map.of("content", List.of(Map.of("type", "text", "text",
                                mapper.writeValueAsString(GitHubIssueMcpServiceTest.issueData()))), "isError", false);
                    }
                    default -> {
                        exchange.sendResponseHeaders(202, -1);
                        return;
                    }
                }
                String json = mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result));
                boolean sse = method.equals("tools/call") && calls.get() == 2;
                byte[] body = (sse ? "event: message\ndata: " + json + "\n\n" : json).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", sse ? "text/event-stream" : "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        server.start();
        var properties = GitHubIssueMcpServiceTest.configured();
        var service = new GitHubIssueMcpService(properties, mapper,
                () -> GitHubIssueMcpService.connect(properties, "http://127.0.0.1:" + server.getAddress().getPort()));
        try {
            assertThat(service.readIssue("spring-projects", "spring-ai", 42).title()).isEqualTo("ChatClient issue");
            assertThat(service.readIssue("spring-projects", "spring-ai", 42).number()).isEqualTo(42);
            assertThat(initializations).hasValue(1);
            assertThat(discoveries).hasValue(1);
            assertThat(calls).hasValue(2);
            assertThat(authHeaders).isNotEmpty().allMatch(value -> value.equals("Bearer test-secret"));
            assertThat(readOnlyHeaders).allMatch(value -> value.equals("true"));
            assertThat(toolHeaders).allMatch(value -> value.equals("issue_read"));
            assertThat(arguments).allSatisfy(params -> {
                assertThat(params.path("name").asText()).isEqualTo("issue_read");
                assertThat(params.path("arguments").path("method").asText()).isEqualTo("get");
                assertThat(params.path("arguments").path("issue_number").asInt()).isEqualTo(42);
                assertThat(params.path("arguments").path("owner").asText()).isEqualTo("spring-projects");
                assertThat(params.path("arguments").path("repo").asText()).isEqualTo("spring-ai");
            });
        } finally {
            service.close();
            server.stop(0);
        }
    }

    @Test
    void shouldReportAuthenticationFailureWithoutKeepingSdkExceptionOrCredential() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp/", exchange -> {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
        var properties = GitHubIssueMcpServiceTest.configured();
        var service = new GitHubIssueMcpService(properties, mapper,
                () -> GitHubIssueMcpService.connect(properties, "http://127.0.0.1:" + server.getAddress().getPort()));
        try {
            assertThatThrownBy(() -> service.readIssue("spring-projects", "spring-ai", 42))
                    .hasMessage("MCP_GITHUB_AUTH_FAILED").hasNoCause();
        } finally {
            service.close();
            server.stop(0);
        }
    }
}
