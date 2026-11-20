package com.jundaodsj.insightops.server.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.report.application.ReportReviewStore.*;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import com.jundaodsj.insightops.server.report.ReportReviewService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reports/{reportId}/review")
public class ReportReviewController {
    private final ReportReviewService service;
    private final ObjectMapper commands;

    public ReportReviewController(ReportReviewService service, ObjectMapper json) {
        this.service = service;
        this.commands = json.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
    }

    @GetMapping
    public ApiResponse<History> history(@PathVariable UUID reportId, HttpServletRequest request) {
        return response(request, service.history(CurrentAccount.actor(request), reportId));
    }

    @PostMapping("/verifications")
    public ApiResponse<Verification> record(@PathVariable UUID reportId, @RequestBody Map<String,Object> body,
                                           HttpServletRequest request) {
        return response(request, service.record(CurrentAccount.actor(request), reportId,
                command(body, ReportReviewService.VerificationCommand.class)));
    }

    @PostMapping("/decisions")
    public ApiResponse<Decision> decide(@PathVariable UUID reportId, @RequestBody Map<String,Object> body,
                                       HttpServletRequest request) {
        return response(request, service.decide(CurrentAccount.actor(request), reportId,
                command(body, ReportReviewService.DecisionCommand.class)));
    }

    private <T> T command(Map<String,Object> body, Class<T> type) {
        try { return commands.convertValue(body, type); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("提交字段或取值无效。"); }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ProblemDetail invalid(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    private static <T> ApiResponse<T> response(HttpServletRequest request, T data) {
        return new ApiResponse<>((String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE), data);
    }
}
