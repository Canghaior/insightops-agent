package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.server.auth.CurrentAccount;
import com.jundaodsj.insightops.server.workflow.AgentWorkflowRunService;
import com.jundaodsj.insightops.tool.application.registry.AgentToolDefinition;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agent-workflows")
public class AgentWorkflowController {

    private final AgentWorkflowRunService service;

    public AgentWorkflowController(AgentWorkflowRunService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<AgentWorkflowRunService.ActiveTemplate>> active(
            HttpServletRequest request) {
        var account = CurrentAccount.account(request);
        return response(request, service.activeTemplates(account.workspaceId()));
    }

    @PostMapping("/{templateId}/runs")
    public ApiResponse<AgentWorkflowRunService.LaunchResult> launch(
            @PathVariable UUID templateId, @Valid @RequestBody LaunchRequest body,
            HttpServletRequest request) {
        var account = CurrentAccount.account(request);
        try {
            return response(request, service.launch(
                    account.actor(), "SYSTEM_ADMIN".equals(account.systemRole()), access(account),
                    templateId, body.expectedVersionId(), body.sessionId(), body.requestId(),
                    body.inputs() == null ? Map.of() : body.inputs(), traceId(request)));
        }
        catch (AgentWorkflowRunService.WorkflowRunException exception) {
            throw status(exception);
        }
    }

    @PostMapping("/runs/{runId}/retries")
    public ApiResponse<AgentWorkflowRunService.LaunchResult> retry(
            @PathVariable UUID runId, @Valid @RequestBody RetryRequest body,
            HttpServletRequest request) {
        var account = CurrentAccount.account(request);
        try {
            return response(request, service.retry(
                    account.actor(), "SYSTEM_ADMIN".equals(account.systemRole()), access(account),
                    runId, body.fromNodeId(), body.requestId(), traceId(request)));
        }
        catch (AgentWorkflowRunService.WorkflowRunException exception) {
            throw status(exception);
        }
    }

    private static AgentToolDefinition.AccessLevel access(
            com.jundaodsj.insightops.identity.application.AccountWorkspaceStore.AccountRecord account) {
        if ("SYSTEM_ADMIN".equals(account.systemRole())) return AgentToolDefinition.AccessLevel.SYSTEM_ADMIN;
        if ("OWNER".equals(account.role())) return AgentToolDefinition.AccessLevel.WORKSPACE_OWNER;
        return AgentToolDefinition.AccessLevel.WORKSPACE_MEMBER;
    }

    private static ResponseStatusException status(IllegalArgumentException exception) {
        HttpStatus status = exception.getMessage() != null
                && exception.getMessage().contains("CHANGED") ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        return new ResponseStatusException(status, exception.getMessage(), exception);
    }

    private static String traceId(HttpServletRequest request) {
        return (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
    }

    private static <T> ApiResponse<T> response(HttpServletRequest request, T value) {
        return new ApiResponse<>(traceId(request), value);
    }

    public record LaunchRequest(
            @NotNull UUID expectedVersionId,
            UUID sessionId,
            @NotNull UUID requestId,
            Map<String, Object> inputs) { }

    public record RetryRequest(
            @NotBlank String fromNodeId,
            @NotNull UUID requestId) { }
}
