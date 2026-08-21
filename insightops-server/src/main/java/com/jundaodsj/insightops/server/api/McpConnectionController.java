package com.jundaodsj.insightops.server.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import com.jundaodsj.insightops.server.tool.McpReadToolService;
import com.jundaodsj.insightops.tool.application.McpConnectionStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/admin/mcp-connections")
public class McpConnectionController {

    private static final Pattern TOOL_NAME = Pattern.compile("[a-zA-Z0-9_.:/-]{1,128}");
    private final McpConnectionStore store;
    private final ObjectMapper objectMapper;

    public McpConnectionController(McpConnectionStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ApiResponse<List<McpConnectionStore.Connection>> list(HttpServletRequest request) {
        requireManager(request);
        return response(request, store.list(CurrentAccount.actor(request)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<McpConnectionStore.Connection> create(
            @Valid @RequestBody ConnectionRequest body, HttpServletRequest request) {
        requireManager(request);
        Validated validated = validated(body);
        try {
            return response(request, store.create(CurrentAccount.actor(request),
                    new McpConnectionStore.CreateCommand(
                            UUID.randomUUID(), body.name().strip(), validated.endpoint(),
                            validated.allowedToolsJson(), body.enabled()), Instant.now()));
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "MCP connection name already exists", exception);
        }
    }

    @PutMapping("/{connectionId}")
    public ApiResponse<McpConnectionStore.Connection> update(
            @PathVariable UUID connectionId, @Valid @RequestBody ConnectionRequest body,
            HttpServletRequest request) {
        requireManager(request);
        Validated validated = validated(body);
        return response(request, store.update(CurrentAccount.actor(request), connectionId,
                new McpConnectionStore.UpdateCommand(
                        body.name().strip(), validated.endpoint(),
                        validated.allowedToolsJson(), body.enabled()), Instant.now())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "MCP connection not found")));
    }

    @DeleteMapping("/{connectionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID connectionId, HttpServletRequest request) {
        requireManager(request);
        if (!store.delete(CurrentAccount.actor(request), connectionId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MCP connection not found");
        }
    }

    private Validated validated(ConnectionRequest body) {
        String endpoint = McpReadToolService.validateEndpoint(body.endpoint()).toString();
        if (body.allowedTools().size() > 20
                || body.allowedTools().keySet().stream().anyMatch(name -> !TOOL_NAME.matcher(name).matches())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid MCP tool allowlist");
        }
        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        body.allowedTools().forEach((name, description) -> normalized.put(
                name, description == null ? "" : description.strip()));
        try { return new Validated(endpoint, objectMapper.writeValueAsString(normalized)); }
        catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid MCP tool allowlist", exception);
        }
    }

    private static void requireManager(HttpServletRequest request) {
        var account = CurrentAccount.account(request);
        if (!"SYSTEM_ADMIN".equals(account.systemRole()) && !"OWNER".equals(account.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "MCP connection management requires a workspace owner");
        }
    }

    private static <T> ApiResponse<T> response(HttpServletRequest request, T data) {
        return new ApiResponse<>((String) request.getAttribute(
                TraceIdFilter.TRACE_ID_ATTRIBUTE), data);
    }

    public record ConnectionRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 1000) String endpoint,
            @NotEmpty Map<@NotBlank @Size(max = 128) String, @Size(max = 500) String> allowedTools,
            boolean enabled) { }

    private record Validated(String endpoint, String allowedToolsJson) { }
}
