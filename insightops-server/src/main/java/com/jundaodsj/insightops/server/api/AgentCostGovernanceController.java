package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.agent.application.AgentCostGovernanceStore;
import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import com.jundaodsj.insightops.server.chat.AgentCostGovernanceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/admin/agent-cost")
public class AgentCostGovernanceController {

    private final AgentCostGovernanceService service;

    public AgentCostGovernanceController(AgentCostGovernanceService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<AgentCostGovernanceStore.Overview> overview(HttpServletRequest request) {
        AccountWorkspaceStore.AccountRecord account = requireManager(request);
        return response(request, service.overview(account.workspaceId()));
    }

    @PutMapping("/policy")
    public ApiResponse<AgentCostGovernanceStore.Policy> update(
            @Valid @RequestBody PolicyRequest body, HttpServletRequest request) {
        AccountWorkspaceStore.AccountRecord account = requireManager(request);
        try {
            return response(request, service.update(account.workspaceId(), account.userId(),
                    new AgentCostGovernanceStore.PolicyUpdate(
                            body.enabled(), body.dailyTokenLimit(), body.dailyCostLimitCny(),
                            body.monthlyTokenLimit(), body.monthlyCostLimitCny(),
                            body.maxConcurrentRuns(), body.warningPercent(), body.hardLimitEnabled())));
        }
        catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    private static AccountWorkspaceStore.AccountRecord requireManager(HttpServletRequest request) {
        AccountWorkspaceStore.AccountRecord account = CurrentAccount.account(request);
        if (!"SYSTEM_ADMIN".equals(account.systemRole()) && !"OWNER".equals(account.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Agent cost governance requires a Workspace owner");
        }
        return account;
    }

    private static <T> ApiResponse<T> response(HttpServletRequest request, T data) {
        return new ApiResponse<>((String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE), data);
    }

    public record PolicyRequest(
            boolean enabled,
            @Min(1) long dailyTokenLimit,
            @NotNull @DecimalMin("0.000001") BigDecimal dailyCostLimitCny,
            @Min(1) long monthlyTokenLimit,
            @NotNull @DecimalMin("0.000001") BigDecimal monthlyCostLimitCny,
            @Min(1) @Max(100) int maxConcurrentRuns,
            @Min(1) @Max(99) int warningPercent,
            boolean hardLimitEnabled) { }
}
