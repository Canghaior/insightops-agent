package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.report.application.BuildEvidence.*;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import com.jundaodsj.insightops.server.report.BuildEvidenceService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartHttpServletRequest;

@RestController
@RequestMapping("/api/v1/reports/{reportId}/review/builds")
public class BuildEvidenceController {
    private final BuildEvidenceService service;
    public BuildEvidenceController(BuildEvidenceService service) { this.service = service; }

    @GetMapping
    public ApiResponse<List<Summary>> list(@PathVariable UUID reportId, HttpServletRequest request) {
        return response(request, service.list(CurrentAccount.actor(request), reportId));
    }

    @GetMapping("/{batchId}")
    public ApiResponse<Batch> detail(@PathVariable UUID reportId, @PathVariable UUID batchId, HttpServletRequest request) {
        return response(request, service.require(CurrentAccount.actor(request), reportId, batchId));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Batch> upload(@PathVariable UUID reportId, @RequestParam String metadata,
                                     MultipartHttpServletRequest request) {
        if (request.getParameterValues("metadata").length != 1 || request.getParameterMap().size() != 1)
            throw new IllegalArgumentException("仅支持一份 metadata 声明，其他材料应选择文件。");
        return response(request, service.importBatch(CurrentAccount.actor(request), reportId, metadata, request.getMultiFileMap()));
    }

    @GetMapping("/{batchId}/files/{fileId}")
    public ResponseEntity<byte[]> original(@PathVariable UUID reportId, @PathVariable UUID batchId,
                                           @PathVariable UUID fileId, HttpServletRequest request) {
        var original = service.download(CurrentAccount.actor(request), reportId, batchId, fileId);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(original.file().size())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(original.file().name(), StandardCharsets.UTF_8).build().toString())
                .body(original.content());
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
