package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import com.jundaodsj.insightops.knowledge.application.QualityReviewStore;
import com.jundaodsj.insightops.knowledge.application.RagEvaluationStore;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import com.jundaodsj.insightops.server.knowledge.RagEvaluationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/quality")
public class QualityReviewAdminController {
    private final QualityReviewStore store;
    private final RagEvaluationService evaluationService;

    public QualityReviewAdminController(QualityReviewStore store,
                                        RagEvaluationService evaluationService) {
        this.store = store;
        this.evaluationService = evaluationService;
    }

    @GetMapping("/feedback")
    public ApiResponse<QualityReviewStore.FeedbackPage> feedback(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            HttpServletRequest request) {
        var account = requireSystemAdmin(request);
        return response(request, store.listFeedback(account.workspaceId(), page, size, status, type));
    }

    @PatchMapping("/feedback/{type}/{feedbackId}")
    public ApiResponse<QualityReviewStore.FeedbackItem> review(
            @PathVariable String type, @PathVariable UUID feedbackId,
            @Valid @RequestBody ReviewRequest body, HttpServletRequest request) {
        var account = requireSystemAdmin(request);
        try {
            var command = new QualityReviewStore.ReviewCommand(body.decision(), body.note(),
                    body.candidate() == null ? null : body.candidate().command());
            return response(request, store.reviewFeedback(account.workspaceId(), account.userId(),
                    feedbackId, type, command, Instant.now()).orElseThrow(
                    () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Feedback not found")));
        }
        catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
        catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        }
    }

    @GetMapping("/candidates")
    public ApiResponse<QualityReviewStore.CandidatePage> candidates(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String status,
            HttpServletRequest request) {
        var account = requireSystemAdmin(request);
        return response(request, store.listCandidates(account.workspaceId(), page, size, status));
    }

    @PutMapping("/candidates/{candidateId}")
    public ApiResponse<QualityReviewStore.EvaluationCandidate> updateCandidate(
            @PathVariable UUID candidateId, @Valid @RequestBody CandidateRequest body,
            HttpServletRequest request) {
        var account = requireSystemAdmin(request);
        try {
            return response(request, store.updateCandidate(account.workspaceId(), candidateId,
                    body.command(), Instant.now()).orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.CONFLICT, "Only a draft candidate can be edited")));
        }
        catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PatchMapping("/candidates/{candidateId}/decision")
    public ApiResponse<QualityReviewStore.EvaluationCandidate> decideCandidate(
            @PathVariable UUID candidateId, @Valid @RequestBody CandidateDecisionRequest body,
            HttpServletRequest request) {
        var account = requireSystemAdmin(request);
        try {
            return response(request, store.decideCandidate(account.workspaceId(), account.userId(),
                    candidateId, body.decision(), body.note(), Instant.now()).orElseThrow(
                    () -> new ResponseStatusException(HttpStatus.CONFLICT,
                            "Only a draft candidate can be reviewed")));
        }
        catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @GetMapping("/dataset-versions")
    public ApiResponse<List<QualityReviewStore.DatasetVersion>> versions(HttpServletRequest request) {
        var account = requireSystemAdmin(request);
        return response(request, store.listVersions(account.workspaceId()));
    }

    @PostMapping("/dataset-versions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<QualityReviewStore.DatasetVersion> createVersion(
            @Valid @RequestBody DatasetVersionRequest body, HttpServletRequest request) {
        var account = requireSystemAdmin(request);
        try {
            return response(request, store.createVersion(account.workspaceId(), account.userId(),
                    body.name(), body.candidateIds(), Instant.now()));
        }
        catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PostMapping("/dataset-versions/{versionId}/evaluate")
    public ApiResponse<RagEvaluationStore.Report> evaluateVersion(
            @PathVariable UUID versionId,
            @Valid @RequestBody(required = false) EvaluationRequest body,
            HttpServletRequest request) {
        var account = requireSystemAdmin(request);
        if (store.datasetSelection(account.workspaceId(), versionId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dataset version not found");
        }
        int sampleSize = body == null || body.generationSampleSize() == null
                ? 3 : body.generationSampleSize();
        boolean judge = body == null || body.judgeFaithfulness() == null
                || body.judgeFaithfulness();
        try {
            return response(request, evaluationService.run(
                    account.workspaceId(), sampleSize, judge, versionId));
        }
        catch (RagEvaluationService.EvaluationAlreadyRunningException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        }
        catch (RagEvaluationService.EvaluationModelUnavailableException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        }
    }

    @PostMapping("/dataset-versions/{versionId}/activate")
    public ApiResponse<QualityReviewStore.DatasetVersion> activate(
            @PathVariable UUID versionId, HttpServletRequest request) {
        var account = requireSystemAdmin(request);
        try {
            return response(request, store.activateVersion(account.workspaceId(), account.userId(),
                    versionId, Instant.now()).orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Dataset version not found")));
        }
        catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        }
    }

    private static AccountWorkspaceStore.AccountRecord requireSystemAdmin(HttpServletRequest request) {
        var account = CurrentAccount.account(request);
        if (!"SYSTEM_ADMIN".equals(account.systemRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Quality review requires a system administrator");
        }
        return account;
    }

    private static <T> ApiResponse<T> response(HttpServletRequest request, T data) {
        return new ApiResponse<>((String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE), data);
    }

    public record ReviewRequest(
            @NotBlank String decision, @Size(max = 1000) String note,
            @Valid CandidateRequest candidate) { }

    public record CandidateRequest(
            @NotBlank @Size(max = 4000) String question,
            boolean expectedAnswerable,
            @Size(max = 128) String expectedProject,
            @NotBlank @Size(max = 64) String category,
            @Size(max = 20) List<@Size(max = 120) String> mustHitTerms,
            @Size(max = 20) List<@Size(max = 120) String> answerMustInclude,
            @Size(max = 255) String sourceDomain) {
        QualityReviewStore.CandidateCommand command() {
            return new QualityReviewStore.CandidateCommand(question, expectedAnswerable,
                    expectedProject, category, mustHitTerms, answerMustInclude, sourceDomain);
        }
    }

    public record CandidateDecisionRequest(
            @NotBlank String decision, @Size(max = 1000) String note) { }

    public record DatasetVersionRequest(
            @NotBlank @Size(max = 128) String name,
            @NotEmpty @Size(max = 100) List<@NotNull UUID> candidateIds) { }

    public record EvaluationRequest(
            @Min(0) @Max(6) Integer generationSampleSize,
            Boolean judgeFaithfulness) { }
}
