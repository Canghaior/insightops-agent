package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.agent.application.AgentEvaluationStore;
import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import com.jundaodsj.insightops.server.evaluation.AgentEvaluationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/agent-evaluations")
public class AgentEvaluationAdminController {

    private final AgentEvaluationService service;

    public AgentEvaluationAdminController(AgentEvaluationService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<OverviewResponse> overview(HttpServletRequest request) {
        var account = requireManager(request);
        return response(request, new OverviewResponse(
                service.overview(account.workspaceId()), service.defaults()));
    }

    @PostMapping("/datasets")
    public ApiResponse<AgentEvaluationStore.Dataset> createDataset(
            @Valid @RequestBody DatasetRequest body, HttpServletRequest request) {
        var account = requireManager(request);
        try {
            return response(request, service.createDataset(account.workspaceId(), account.userId(),
                    new AgentEvaluationStore.DatasetDraft(
                            body.name(), body.description(), body.gate().gate(),
                            body.cases().stream().map(CaseRequest::draft).toList())));
        }
        catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PostMapping("/datasets/{datasetId}/derive-from-run")
    public ApiResponse<AgentEvaluationStore.Dataset> deriveFromRun(
            @PathVariable UUID datasetId,
            @Valid @RequestBody DeriveCaseRequest body,
            HttpServletRequest request) {
        var account = requireManager(request);
        try {
            AgentEvaluationStore.CaseDraft draft = body.evaluationCase().draft();
            return response(request, service.deriveFromRun(
                    account.actor(), account.userId(), datasetId, body.sourceRunId(), draft));
        }
        catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PostMapping("/candidates")
    public ApiResponse<AgentEvaluationStore.Candidate> createCandidate(
            @Valid @RequestBody CandidateRequest body, HttpServletRequest request) {
        var account = requireManager(request);
        try {
            return response(request, service.createCandidate(account.workspaceId(), account.userId(),
                    new AgentEvaluationStore.CandidateDraft(
                            body.name(), body.plannerPromptAppendix(), body.modelName(),
                            body.temperature(), body.maxOutputTokens(), "", body.basedOnId())));
        }
        catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PostMapping("/runs")
    public ApiResponse<AgentEvaluationStore.EvaluationRun> startEvaluation(
            @Valid @RequestBody StartEvaluationRequest body, HttpServletRequest request) {
        var account = requireManager(request);
        try {
            return response(request, service.startEvaluation(
                    account.workspaceId(), account.userId(), body.datasetId(), body.candidateId()));
        }
        catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
        catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        }
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<AgentEvaluationStore.EvaluationRun> runDetail(
            @PathVariable UUID runId, HttpServletRequest request) {
        var account = requireManager(request);
        try {
            return response(request, service.detail(account.workspaceId(), runId));
        }
        catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    @PostMapping("/candidates/{candidateId}/activate")
    public ApiResponse<AgentEvaluationStore.Candidate> activate(
            @PathVariable UUID candidateId,
            @Valid @RequestBody(required = false) ActivationRequest body,
            HttpServletRequest request) {
        var account = requireManager(request);
        try {
            return response(request, service.activate(
                    account.workspaceId(), account.userId(), candidateId,
                    body == null ? "Evaluation gate passed" : body.reason()));
        }
        catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
        catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        }
    }

    private static AccountWorkspaceStore.AccountRecord requireManager(HttpServletRequest request) {
        var account = CurrentAccount.account(request);
        if (!"SYSTEM_ADMIN".equals(account.systemRole()) && !"OWNER".equals(account.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Agent evaluation governance requires a Workspace owner");
        }
        return account;
    }

    private static <T> ApiResponse<T> response(HttpServletRequest request, T data) {
        return new ApiResponse<>((String) request.getAttribute(
                TraceIdFilter.TRACE_ID_ATTRIBUTE), data);
    }

    public record OverviewResponse(
            AgentEvaluationStore.Overview governance,
            AgentEvaluationService.Defaults defaults) {
    }

    public record DatasetRequest(
            @NotBlank @Size(max = 128) String name,
            @Size(max = 1000) String description,
            @NotNull @Valid GateRequest gate,
            @NotEmpty @Size(max = 100) List<@Valid CaseRequest> cases) {
    }

    public record GateRequest(
            @DecimalMin("0") @DecimalMax("1") double minimumSuccessRate,
            @DecimalMin("0") @DecimalMax("1") double minimumToolAccuracy,
            @DecimalMin("0") @DecimalMax("1") double minimumRecoveryRate,
            @DecimalMin("0") @DecimalMax("1") double minimumCitationRate,
            @Min(1) long maxAverageDurationMs,
            @Min(1) long maxAverageTokens,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal maxAverageCostCny) {

        AgentEvaluationStore.Gate gate() {
            return new AgentEvaluationStore.Gate(
                    minimumSuccessRate, minimumToolAccuracy, minimumRecoveryRate,
                    minimumCitationRate, maxAverageDurationMs, maxAverageTokens,
                    maxAverageCostCny);
        }
    }

    public record CaseRequest(
            @NotBlank @Size(max = 96) String caseKey,
            @Size(max = 4000) String question,
            @Size(max = 20) List<@NotBlank @Size(max = 64) String> expectedTools,
            @Size(max = 20) List<@NotBlank @Size(max = 64) String> forbiddenTools,
            @Size(max = 20) List<@NotBlank @Size(max = 253) String> requiredSourceDomains,
            boolean expectRecovery,
            @Min(1) @Max(12) int maxToolRounds,
            @Min(1) long maxDurationMs,
            @Min(1) long maxTokens,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal maxCostCny,
            boolean required) {

        AgentEvaluationStore.CaseDraft draft() {
            return new AgentEvaluationStore.CaseDraft(
                    caseKey, question == null ? "" : question,
                    expectedTools == null ? List.of() : expectedTools,
                    forbiddenTools == null ? List.of() : forbiddenTools,
                    requiredSourceDomains == null ? List.of() : requiredSourceDomains,
                    expectRecovery, maxToolRounds, maxDurationMs, maxTokens,
                    maxCostCny, required, null);
        }
    }

    public record DeriveCaseRequest(
            @NotNull UUID sourceRunId,
            @NotNull @Valid CaseRequest evaluationCase) {
    }

    public record CandidateRequest(
            @NotBlank @Size(max = 128) String name,
            @Size(max = 8000) String plannerPromptAppendix,
            @NotBlank @Size(max = 128) String modelName,
            @DecimalMin("0") @DecimalMax("2") double temperature,
            @Min(1) @Max(8192) int maxOutputTokens,
            UUID basedOnId) {
    }

    public record StartEvaluationRequest(
            @NotNull UUID datasetId,
            @NotNull UUID candidateId) {
    }

    public record ActivationRequest(@Size(max = 500) String reason) {
    }
}
