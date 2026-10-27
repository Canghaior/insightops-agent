package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.report.application.ReportDeliveryStore;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import com.jundaodsj.insightops.server.report.ReportService;
import com.jundaodsj.insightops.server.report.ResearchReportService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

@RestController
public class ResearchReportController {
    private final ResearchReportService service;
    public ResearchReportController(ResearchReportService service) { this.service = service; }

    @PostMapping("/api/v1/runs/{runId}/report")
    public ApiResponse<ReportDeliveryStore.ReportRecord> create(@PathVariable UUID runId, HttpServletRequest request) {
        try {
            return new ApiResponse<>((String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE),
                    service.create(CurrentAccount.actor(request), runId));
        } catch (ReportService.ReportNotFoundException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "研究记录不存在");
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        }
    }
}
