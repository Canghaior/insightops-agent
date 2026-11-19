package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.server.auth.CurrentAccount;
import com.jundaodsj.insightops.server.workflow.SystemDecisionAssessmentService;
import com.jundaodsj.insightops.tool.application.registry.AgentToolDefinition.AccessLevel;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/business-systems/{systemId}/decision-runs")
public class SystemDecisionAssessmentController {
    private final SystemDecisionAssessmentService service;

    public SystemDecisionAssessmentController(SystemDecisionAssessmentService service) { this.service = service; }

    @PostMapping
    public ApiResponse<?> launch(@PathVariable UUID systemId, @RequestParam int expectedRevision,
                                 @RequestBody SystemDecisionAssessmentService.Command command, HttpServletRequest request) {
        var account = CurrentAccount.account(request);
        boolean admin = "SYSTEM_ADMIN".equals(account.systemRole());
        var access = admin ? AccessLevel.SYSTEM_ADMIN : "OWNER".equals(account.role())
                ? AccessLevel.WORKSPACE_OWNER : AccessLevel.WORKSPACE_MEMBER;
        String trace = (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        try {
            return new ApiResponse<>(trace, service.launch(account.actor(), admin, access, systemId, expectedRevision, command, trace));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }
}
