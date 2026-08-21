package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.agent.application.AgentRunQuery;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/runs")
public class AgentRunController {

    private static final Set<String> ALLOWED_STATUSES = Set.of(
            "CREATED", "RUNNING", "PAUSED", "SUCCEEDED", "FAILED", "CANCELLED");

    private final AgentRunQuery agentRunQuery;

    public AgentRunController(AgentRunQuery agentRunQuery) {
        this.agentRunQuery = agentRunQuery;
    }

    @GetMapping
    public ApiResponse<AgentRunQuery.RunPage> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String status,
            HttpServletRequest request) {
        return new ApiResponse<>(traceId(request), agentRunQuery.listRuns(
                CurrentAccount.actor(request), page, size, status(status)));
    }

    @GetMapping("/{runId}")
    public ApiResponse<AgentRunQuery.RunDetail> detail(
            @PathVariable UUID runId,
            HttpServletRequest request) {
        AgentRunQuery.RunDetail detail = agentRunQuery.findRun(CurrentAccount.actor(request), runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent run not found"));
        return new ApiResponse<>(traceId(request), detail);
    }

    private static String status(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_STATUSES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported run status");
        }
        return normalized;
    }

    private static String traceId(HttpServletRequest request) {
        return (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
    }
}
