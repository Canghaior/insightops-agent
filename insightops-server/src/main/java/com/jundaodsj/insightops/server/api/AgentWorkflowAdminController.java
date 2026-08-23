package com.jundaodsj.insightops.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.jundaodsj.insightops.agent.application.AgentWorkflowTemplateStore;
import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import com.jundaodsj.insightops.server.workflow.AgentWorkflowService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/agent-workflows")
public class AgentWorkflowAdminController {

    private final AgentWorkflowService service;

    public AgentWorkflowAdminController(AgentWorkflowService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<AgentWorkflowService.Overview> overview(HttpServletRequest request) {
        var account = requireManager(request);
        return response(request, service.overview(account.workspaceId(), account.userId()));
    }

    @PostMapping("/preview")
    public ApiResponse<AgentWorkflowService.Preview> preview(
            @Valid @RequestBody GraphRequest body, HttpServletRequest request) {
        requireManager(request);
        try {
            return response(request, service.preview(body.graph().toString()));
        }
        catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PostMapping("/templates")
    public ApiResponse<AgentWorkflowTemplateStore.WorkflowTemplate> create(
            @Valid @RequestBody TemplateRequest body, HttpServletRequest request) {
        var account = requireManager(request);
        try {
            return response(request, service.create(account.workspaceId(), account.userId(),
                    new AgentWorkflowTemplateStore.TemplateDraft(
                            body.name(), body.description(), body.category(), body.version().draft())));
        }
        catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PostMapping("/templates/{templateId}/versions")
    public ApiResponse<AgentWorkflowTemplateStore.WorkflowTemplate> createVersion(
            @PathVariable UUID templateId,
            @Valid @RequestBody VersionRequest body,
            HttpServletRequest request) {
        var account = requireManager(request);
        try {
            return response(request, service.createVersion(
                    account.workspaceId(), templateId, account.userId(), body.draft()));
        }
        catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PostMapping("/templates/{templateId}/versions/{versionId}/activate")
    public ApiResponse<AgentWorkflowTemplateStore.WorkflowTemplate> activate(
            @PathVariable UUID templateId,
            @PathVariable UUID versionId,
            @Valid @RequestBody(required = false) ActivationRequest body,
            HttpServletRequest request) {
        var account = requireManager(request);
        try {
            return response(request, service.activate(
                    account.workspaceId(), templateId, versionId, account.userId(),
                    body == null ? "Workflow reviewed" : body.reason()));
        }
        catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    private static AccountWorkspaceStore.AccountRecord requireManager(HttpServletRequest request) {
        var account = CurrentAccount.account(request);
        if (!"SYSTEM_ADMIN".equals(account.systemRole()) && !"OWNER".equals(account.role())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Workflow governance requires a Workspace owner");
        }
        return account;
    }

    private static <T> ApiResponse<T> response(HttpServletRequest request, T data) {
        return new ApiResponse<>((String) request.getAttribute(
                TraceIdFilter.TRACE_ID_ATTRIBUTE), data);
    }

    public record GraphRequest(@NotNull JsonNode graph) { }

    public record TemplateRequest(
            @NotBlank @Size(max = 128) String name,
            @Size(max = 1000) String description,
            @NotBlank @Size(max = 48) String category,
            @NotNull @Valid VersionRequest version) { }

    public record VersionRequest(
            @Size(max = 500) String summary,
            @NotBlank @Size(max = 4000) String entryQuestion,
            @NotNull JsonNode graph) {
        AgentWorkflowTemplateStore.VersionDraft draft() {
            return new AgentWorkflowTemplateStore.VersionDraft(
                    summary, entryQuestion, graph.toString());
        }
    }

    public record ActivationRequest(@Size(max = 500) String reason) { }
}
