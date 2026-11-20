package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.report.application.BuildEvidenceComparison;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import com.jundaodsj.insightops.server.report.BuildComparisonService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reports/{reportId}/review/comparison")
public class BuildComparisonController {
    private final BuildComparisonService service;

    public BuildComparisonController(BuildComparisonService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<BuildEvidenceComparison> comparison(@PathVariable UUID reportId, @RequestParam UUID baselineId,
                                                         @RequestParam UUID candidateId, HttpServletRequest request) {
        try {
            var result = service.compare(CurrentAccount.actor(request), reportId, baselineId, candidateId);
            return new ApiResponse<>((String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE), result);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }
}
