package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.report.application.ReportDeliveryStore;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import com.jundaodsj.insightops.server.report.ReportService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {
    private final ReportDeliveryStore store;
    private final ReportService service;

    public ReportController(ReportDeliveryStore store, ReportService service) {
        this.store = store;
        this.service = service;
    }

    @GetMapping
    public ApiResponse<ReportDeliveryStore.ReportPage> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        return response(request, store.listReports(CurrentAccount.actor(request), page, size));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ReportDeliveryStore.ReportRecord> create(
            @Valid @RequestBody CreateReportRequest body, HttpServletRequest request) {
        try {
            return response(request, service.create(CurrentAccount.actor(request), body.title(),
                    body.periodStart(), body.periodEnd(), body.projectIds(), body.eventTypes(),
                    body.maxItems(), Instant.now()));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @GetMapping("/{reportId}/export.md")
    public ResponseEntity<byte[]> markdown(@PathVariable UUID reportId, HttpServletRequest request) {
        try {
            byte[] body = service.markdown(CurrentAccount.actor(request), reportId)
                    .getBytes(StandardCharsets.UTF_8);
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition(reportId, "md")).body(body);
        } catch (ReportService.ReportNotFoundException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Report not found");
        }
    }

    @GetMapping("/{reportId}/export.pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID reportId, HttpServletRequest request) {
        try {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition(reportId, "pdf"))
                    .body(service.pdf(CurrentAccount.actor(request), reportId));
        } catch (ReportService.ReportNotFoundException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Report not found");
        }
    }

    private static String disposition(UUID id, String extension) {
        return ContentDisposition.attachment().filename(
                "insightops-report-" + id + "." + extension, StandardCharsets.UTF_8).build().toString();
    }

    public record CreateReportRequest(
            @NotBlank @Size(max = 200) String title,
            @NotNull Instant periodStart,
            @NotNull Instant periodEnd,
            @Size(max = 100) List<@NotNull UUID> projectIds,
            @Size(max = 4) List<@NotBlank String> eventTypes,
            @Min(1) @Max(100) int maxItems) {
        public CreateReportRequest {
            projectIds = projectIds == null ? List.of() : List.copyOf(projectIds);
            eventTypes = eventTypes == null ? List.of() : List.copyOf(eventTypes);
            if (maxItems == 0) maxItems = 50;
        }
    }

    private static <T> ApiResponse<T> response(HttpServletRequest request, T data) {
        return new ApiResponse<>((String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE), data);
    }
}
