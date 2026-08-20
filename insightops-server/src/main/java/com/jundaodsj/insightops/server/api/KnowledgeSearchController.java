package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.server.auth.CurrentAccount;
import com.jundaodsj.insightops.server.knowledge.KnowledgeSearchService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeSearchController {
    private final KnowledgeSearchService service;

    public KnowledgeSearchController(KnowledgeSearchService service) {
        this.service = service;
    }

    @PostMapping("/search")
    public ApiResponse<KnowledgeSearchService.SearchResponse> search(@Valid @RequestBody SearchRequest input,
                                                                     HttpServletRequest request) {
        var account = CurrentAccount.account(request);
        var result = service.searchForUser(null, account.workspaceId(), account.userId(),
                "SYSTEM_ADMIN".equals(account.systemRole()), input.query().strip(),
                input.limit() == null ? 8 : input.limit());
        return new ApiResponse<>((String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE), result);
    }

    @ExceptionHandler(KnowledgeSearchService.EmbeddingUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ProblemDetail unavailable(KnowledgeSearchService.EmbeddingUnavailableException exception,
                                     HttpServletRequest request) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        detail.setTitle("Embedding unavailable");
        detail.setProperty("code", "EMBEDDING_UNAVAILABLE");
        detail.setProperty("traceId", request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE));
        return detail;
    }

    public record SearchRequest(@NotBlank @Size(max = 2000) String query,
                                @Min(1) @Max(20) Integer limit) {
    }
}
