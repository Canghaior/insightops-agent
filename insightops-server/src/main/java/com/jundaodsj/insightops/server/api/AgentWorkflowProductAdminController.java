package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.agent.application.AgentWorkflowTemplateStore;
import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import com.jundaodsj.insightops.server.workflow.AgentWorkflowProductService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/agent-workflow-products")
public class AgentWorkflowProductAdminController {

    private final AgentWorkflowProductService service;

    public AgentWorkflowProductAdminController(AgentWorkflowProductService service) {
        this.service = service;
    }

    @GetMapping("/templates/{templateId}/analytics")
    public ApiResponse<AgentWorkflowProductService.TemplateAnalytics> analytics(
            @PathVariable UUID templateId,
            @RequestParam(defaultValue = "30") @Min(7) @Max(365) int days,
            HttpServletRequest request) {
        AccountWorkspaceStore.AccountRecord account = requireManager(request);
        return call(request, () -> service.analytics(account.workspaceId(), templateId, days));
    }

    @GetMapping("/templates/{templateId}/versions/{versionId}/export")
    public ApiResponse<AgentWorkflowProductService.ExportBundle> export(
            @PathVariable UUID templateId,
            @PathVariable UUID versionId,
            HttpServletRequest request) {
        AccountWorkspaceStore.AccountRecord account = requireManager(request);
        return call(request, () -> service.exportBundle(
                account.workspaceId(), templateId, versionId));
    }

    @PostMapping("/imports")
    public ApiResponse<AgentWorkflowTemplateStore.WorkflowTemplate> importBundle(
            @Valid @RequestBody ImportRequest body,
            HttpServletRequest request) {
        AccountWorkspaceStore.AccountRecord account = requireManager(request);
        return call(request, () -> service.importBundle(
                account.workspaceId(), account.userId(), body.bundle(), body.name()));
    }

    @GetMapping("/templates/{templateId}/shares")
    public ApiResponse<List<AgentWorkflowProductService.ShareView>> shares(
            @PathVariable UUID templateId, HttpServletRequest request) {
        AccountWorkspaceStore.AccountRecord account = requireManager(request);
        return call(request, () -> service.shares(account.workspaceId(), templateId));
    }

    @PostMapping("/templates/{templateId}/versions/{versionId}/shares")
    public ApiResponse<AgentWorkflowProductService.CreatedShare> createShare(
            @PathVariable UUID templateId,
            @PathVariable UUID versionId,
            @Valid @RequestBody CreateShareRequest body,
            HttpServletRequest request) {
        AccountWorkspaceStore.AccountRecord account = requireManager(request);
        return call(request, () -> service.createShare(
                account.workspaceId(), account.userId(), templateId, versionId,
                body.expiresInDays()));
    }

    @DeleteMapping("/shares/{shareId}")
    public ApiResponse<Map<String, Boolean>> revokeShare(
            @PathVariable UUID shareId, HttpServletRequest request) {
        AccountWorkspaceStore.AccountRecord account = requireManager(request);
        return call(request, () -> {
            service.revokeShare(account.workspaceId(), shareId);
            return Map.of("revoked", true);
        });
    }

    @PostMapping("/shared/preview")
    public ApiResponse<AgentWorkflowProductService.SharedPreview> shared(
            @Valid @RequestBody ShareTokenRequest body, HttpServletRequest request) {
        requireManager(request);
        return call(request, () -> service.sharedPreview(body.token()));
    }

    @PostMapping("/shared/imports")
    public ApiResponse<AgentWorkflowTemplateStore.WorkflowTemplate> importShare(
            @Valid @RequestBody SharedImportRequest body,
            HttpServletRequest request) {
        AccountWorkspaceStore.AccountRecord account = requireManager(request);
        return call(request, () -> service.importShare(
                account.workspaceId(), account.userId(), body.token(), body.name()));
    }

    private <T> ApiResponse<T> call(HttpServletRequest request, Operation<T> operation) {
        try {
            return response(request, operation.run());
        }
        catch (AgentWorkflowProductService.WorkflowProductException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
        catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    private static AccountWorkspaceStore.AccountRecord requireManager(HttpServletRequest request) {
        AccountWorkspaceStore.AccountRecord account = CurrentAccount.account(request);
        if (!"SYSTEM_ADMIN".equals(account.systemRole()) && !"OWNER".equals(account.role())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Workflow governance requires a Workspace owner");
        }
        return account;
    }

    private static <T> ApiResponse<T> response(HttpServletRequest request, T value) {
        return new ApiResponse<>((String) request.getAttribute(
                TraceIdFilter.TRACE_ID_ATTRIBUTE), value);
    }

    @FunctionalInterface
    private interface Operation<T> {
        T run();
    }

    public record ImportRequest(
            @NotNull AgentWorkflowProductService.ExportBundle bundle,
            @NotBlank @Size(max = 128) String name) {
    }

    public record CreateShareRequest(@Min(1) @Max(90) int expiresInDays) { }

    public record ShareTokenRequest(@NotBlank @Size(max = 128) String token) { }

    public record SharedImportRequest(
            @NotBlank @Size(max = 128) String token,
            @NotBlank @Size(max = 128) String name) { }
}
