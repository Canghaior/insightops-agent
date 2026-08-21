package com.jundaodsj.insightops.server.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.tool.application.McpConnectionStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class McpReadToolService {

    private static final int MAX_RESPONSE_BYTES = 200_000;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private final McpConnectionStore connectionStore;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public McpReadToolService(McpConnectionStore connectionStore, ObjectMapper objectMapper) {
        this(connectionStore, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    McpReadToolService(
            McpConnectionStore connectionStore, ObjectMapper objectMapper,
            HttpClient httpClient) {
        this.connectionStore = connectionStore;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public Map<String, Object> call(
            UUID workspaceId, UUID connectionId, String toolName, Map<String, Object> arguments) {
        McpConnectionStore.Connection connection = connectionStore
                .resolveEnabled(workspaceId, connectionId, toolName)
                .orElseThrow(() -> new McpToolException(
                        "MCP_TOOL_NOT_ALLOWED", "MCP connection or tool is not enabled"));
        URI endpoint = safeEndpoint(connection.endpoint());
        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0",
                "id", UUID.randomUUID().toString(),
                "method", "tools/call",
                "params", Map.of("name", toolName, "arguments", arguments));
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(25))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json(body)))
                .build();
        try {
            HttpResponse<InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream responseBody = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new McpToolException("MCP_HTTP_ERROR",
                            "MCP returned HTTP " + response.statusCode());
                }
                byte[] bytes = responseBody.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    throw new McpToolException(
                            "MCP_RESPONSE_TOO_LARGE", "MCP response is too large");
                }
                Map<String, Object> envelope = objectMapper.readValue(
                        new String(bytes, StandardCharsets.UTF_8), MAP_TYPE);
                if (envelope.containsKey("error")) {
                    throw new McpToolException(
                            "MCP_REMOTE_ERROR", "MCP returned a JSON-RPC error");
                }
                Object result = envelope.get("result");
                if (!(result instanceof Map<?, ?> resultMap)) {
                    throw new McpToolException(
                            "MCP_RESPONSE_INVALID", "MCP result must be an object");
                }
                LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
                resultMap.forEach((key, value) -> normalized.put(String.valueOf(key), value));
                return Map.of(
                        "connection", connection.name(),
                        "toolName", toolName,
                        "result", Collections.unmodifiableMap(normalized));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new McpToolException("MCP_INTERRUPTED", "MCP call was interrupted", exception);
        } catch (IOException exception) {
            throw new McpToolException("MCP_UNAVAILABLE", "MCP endpoint is unavailable", exception);
        }
    }

    public static URI validateEndpoint(String value) {
        URI uri;
        try { uri = URI.create(value == null ? "" : value.strip()); }
        catch (IllegalArgumentException exception) {
            throw new McpToolException("MCP_ENDPOINT_INVALID", "Invalid MCP endpoint", exception);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
            throw new McpToolException("MCP_ENDPOINT_INVALID",
                    "MCP endpoint must be HTTPS on port 443 without credentials or fragments");
        }
        if ("localhost".equalsIgnoreCase(uri.getHost())) {
            throw new McpToolException("MCP_ENDPOINT_PRIVATE", "Private MCP endpoints are not allowed");
        }
        return uri;
    }

    private static URI safeEndpoint(String value) {
        URI uri = validateEndpoint(value);
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (privateAddress(address)) {
                    throw new McpToolException(
                            "MCP_ENDPOINT_PRIVATE", "Private MCP endpoints are not allowed");
                }
            }
        } catch (IOException exception) {
            throw new McpToolException("MCP_DNS_FAILED", "Unable to resolve MCP endpoint", exception);
        }
        return uri;
    }

    private static boolean privateAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return first == 0 || first == 10 || first == 127
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 169 && second == 254)
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168)
                    || first >= 224;
        }
        return address instanceof Inet6Address
                && ((bytes[0] & 0xfe) == 0xfc || (bytes[0] == (byte) 0xfe
                && (bytes[1] & 0xc0) == 0x80));
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) {
            throw new McpToolException("MCP_REQUEST_INVALID", "Unable to serialize MCP request", exception);
        }
    }

    public static final class McpToolException extends RuntimeException {
        private final String code;
        public McpToolException(String code, String message) { super(message); this.code = code; }
        public McpToolException(String code, String message, Throwable cause) {
            super(message, cause); this.code = code;
        }
        public String code() { return code; }
    }
}
