package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.server.auth.CurrentAccount;
import com.jundaodsj.insightops.tool.application.AgentToolApprovalStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agent/approvals")
public class AgentToolApprovalController {

    private final AgentToolApprovalStore store;

    public AgentToolApprovalController(AgentToolApprovalStore store) {
        this.store = store;
    }

    @GetMapping
    public ApiResponse<List<AgentToolApprovalStore.Approval>> list(
            @RequestParam(required = false) String status, HttpServletRequest request) {
        return response(request, store.list(CurrentAccount.actor(request), status));
    }

    @GetMapping("/{approvalId}")
    public ApiResponse<AgentToolApprovalStore.Approval> get(
            @PathVariable UUID approvalId, HttpServletRequest request) {
        return response(request, store.find(CurrentAccount.actor(request), approvalId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Approval not found")));
    }

    @PostMapping("/{approvalId}/approve")
    public ApiResponse<AgentToolApprovalStore.Approval> approve(
            @PathVariable UUID approvalId, @Valid @RequestBody DecisionRequest body,
            HttpServletRequest request) {
        return response(request, decision(() -> store.approve(
                CurrentAccount.actor(request), approvalId, body.comment(), Instant.now())));
    }

    @PostMapping("/{approvalId}/reject")
    public ApiResponse<AgentToolApprovalStore.Approval> reject(
            @PathVariable UUID approvalId, @Valid @RequestBody DecisionRequest body,
            HttpServletRequest request) {
        return response(request, decision(() -> store.reject(
                CurrentAccount.actor(request), approvalId, body.comment(), Instant.now())));
    }

    @PostMapping("/{approvalId}/compensate")
    public ApiResponse<AgentToolApprovalStore.Approval> compensate(
            @PathVariable UUID approvalId, @Valid @RequestBody DecisionRequest body,
            HttpServletRequest request) {
        return response(request, decision(() -> store.compensate(
                CurrentAccount.actor(request), approvalId, body.comment(), Instant.now())));
    }

    private static AgentToolApprovalStore.Approval decision(
            java.util.function.Supplier<AgentToolApprovalStore.Approval> action) {
        try { return action.get(); }
        catch (AgentToolApprovalStore.ApprovalException exception) {
            HttpStatus status = "APPROVAL_NOT_FOUND".equals(exception.code())
                    ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
            throw new ResponseStatusException(status, exception.getMessage(), exception);
        }
    }

    private static <T> ApiResponse<T> response(HttpServletRequest request, T data) {
        return new ApiResponse<>((String) request.getAttribute(
                TraceIdFilter.TRACE_ID_ATTRIBUTE), data);
    }

    public record DecisionRequest(@Size(max = 500) String comment) { }
}
