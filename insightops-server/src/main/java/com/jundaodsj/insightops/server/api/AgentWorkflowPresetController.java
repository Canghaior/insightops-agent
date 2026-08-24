package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.server.auth.CurrentAccount;
import com.jundaodsj.insightops.server.workflow.AgentWorkflowProductService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
@RequestMapping("/api/v1/agent-workflow-presets")
public class AgentWorkflowPresetController {

    private final AgentWorkflowProductService service;

    public AgentWorkflowPresetController(AgentWorkflowProductService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<AgentWorkflowProductService.ParameterPresetView>> list(
            @RequestParam UUID templateId,
            @RequestParam UUID versionId,
            HttpServletRequest request) {
        try {
            return response(request, service.presets(
                    CurrentAccount.actor(request), templateId, versionId));
        }
        catch (AgentWorkflowProductService.WorkflowProductException exception) {
            throw badRequest(exception);
        }
    }

    @PostMapping
    public ApiResponse<AgentWorkflowProductService.ParameterPresetView> save(
            @Valid @RequestBody SavePresetRequest body,
            HttpServletRequest request) {
        try {
            return response(request, service.savePreset(
                    CurrentAccount.actor(request), body.templateId(), body.versionId(),
                    body.name(), body.values()));
        }
        catch (AgentWorkflowProductService.WorkflowProductException exception) {
            throw badRequest(exception);
        }
    }

    @DeleteMapping("/{presetId}")
    public ApiResponse<Map<String, Boolean>> delete(
            @PathVariable UUID presetId, HttpServletRequest request) {
        try {
            service.deletePreset(CurrentAccount.actor(request), presetId);
            return response(request, Map.of("deleted", true));
        }
        catch (AgentWorkflowProductService.WorkflowProductException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    private static ResponseStatusException badRequest(IllegalArgumentException exception) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
    }

    private static <T> ApiResponse<T> response(HttpServletRequest request, T value) {
        return new ApiResponse<>((String) request.getAttribute(
                TraceIdFilter.TRACE_ID_ATTRIBUTE), value);
    }

    public record SavePresetRequest(
            @NotNull UUID templateId,
            @NotNull UUID versionId,
            @NotBlank @Size(max = 80) String name,
            @NotNull Map<String, Object> values) {
    }
}
