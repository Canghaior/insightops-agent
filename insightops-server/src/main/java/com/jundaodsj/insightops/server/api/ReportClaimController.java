package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.server.auth.CurrentAccount;
import com.jundaodsj.insightops.server.report.ReportClaimReviews;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reports/{reportId}/claim-reviews")
public class ReportClaimController {
    private final ReportClaimReviews service;

    public ReportClaimController(ReportClaimReviews service) { this.service = service; }

    @GetMapping
    ApiResponse<List<ReportClaimReviews.Saved>> list(@PathVariable UUID reportId, HttpServletRequest request) {
        return new ApiResponse<>((String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE),
                service.list(CurrentAccount.actor(request), reportId));
    }

    @PostMapping
    ApiResponse<ReportClaimReviews.Saved> add(@PathVariable UUID reportId, @RequestBody ReportClaimReviews.Command command,
                                              HttpServletRequest request) {
        return new ApiResponse<>((String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE),
                service.add(CurrentAccount.actor(request), reportId, command));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ProblemDetail invalid(IllegalArgumentException e) { return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage()); }
}
