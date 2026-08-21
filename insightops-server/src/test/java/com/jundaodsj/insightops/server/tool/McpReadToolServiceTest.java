package com.jundaodsj.insightops.server.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.tool.application.McpConnectionStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpReadToolServiceTest {

    @Test
    void shouldAcceptOnlyPublicHttpsShape() {
        assertThat(McpReadToolService.validateEndpoint(
                "https://mcp.example.com/mcp").toString())
                .isEqualTo("https://mcp.example.com/mcp");
        assertThatThrownBy(() -> McpReadToolService.validateEndpoint(
                "http://mcp.example.com/mcp"))
                .isInstanceOf(McpReadToolService.McpToolException.class);
        assertThatThrownBy(() -> McpReadToolService.validateEndpoint(
                "https://localhost/mcp"))
                .isInstanceOf(McpReadToolService.McpToolException.class);
        assertThatThrownBy(() -> McpReadToolService.validateEndpoint(
                "https://mcp.example.com:8443/mcp"))
                .isInstanceOf(McpReadToolService.McpToolException.class);
        assertThatThrownBy(() -> McpReadToolService.validateEndpoint(
                "https://token@mcp.example.com/mcp"))
                .isInstanceOf(McpReadToolService.McpToolException.class);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldStopReadingOversizedResponses() throws Exception {
        UUID workspaceId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        McpConnectionStore store = mock(McpConnectionStore.class);
        when(store.resolveEnabled(workspaceId, connectionId, "read_status"))
                .thenReturn(Optional.of(new McpConnectionStore.Connection(
                        connectionId, "test", "https://8.8.8.8/mcp",
                        "{\"read_status\":\"status\"}", true,
                        Instant.EPOCH, Instant.EPOCH)));
        HttpClient client = mock(HttpClient.class);
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(
                new ByteArrayInputStream(new byte[200_001]));
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        McpReadToolService service = new McpReadToolService(
                store, new ObjectMapper(), client);

        assertThatThrownBy(() -> service.call(
                workspaceId, connectionId, "read_status", Map.of()))
                .isInstanceOf(McpReadToolService.McpToolException.class)
                .extracting(error -> ((McpReadToolService.McpToolException) error).code())
                .isEqualTo("MCP_RESPONSE_TOO_LARGE");
    }

    @Test
    void shouldDeclareSpringInjectionConstructor() {
        var constructor = Arrays.stream(McpReadToolService.class.getDeclaredConstructors())
                .filter(candidate -> candidate.getParameterCount() == 2)
                .findFirst().orElseThrow();
        assertThat(constructor.isAnnotationPresent(Autowired.class)).isTrue();
    }

    @Test
    void shouldRejectPrivateAddressAfterResolution() {
        UUID workspaceId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        McpConnectionStore store = mock(McpConnectionStore.class);
        when(store.resolveEnabled(workspaceId, connectionId, "read_status"))
                .thenReturn(Optional.of(new McpConnectionStore.Connection(
                        connectionId, "private", "https://127.0.0.1/mcp",
                        "{\"read_status\":\"status\"}", true,
                        Instant.EPOCH, Instant.EPOCH)));
        McpReadToolService service = new McpReadToolService(
                store, new ObjectMapper(), mock(HttpClient.class));

        assertThatThrownBy(() -> service.call(
                workspaceId, connectionId, "read_status", Map.of()))
                .isInstanceOf(McpReadToolService.McpToolException.class)
                .extracting(error -> ((McpReadToolService.McpToolException) error).code())
                .isEqualTo("MCP_ENDPOINT_PRIVATE");
    }
}
