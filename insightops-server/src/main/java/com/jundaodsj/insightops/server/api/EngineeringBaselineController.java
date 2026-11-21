package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.businesssystem.application.EngineeringBaselineStore.*;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import com.jundaodsj.insightops.server.businesssystem.EngineeringBaselineService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/v1/business-systems/{system}/engineering-baselines")
public class EngineeringBaselineController {
    private final EngineeringBaselineService service;

    public EngineeringBaselineController(EngineeringBaselineService service) {
        this.service = service;
    }

    private static <T> ApiResponse<T> ok(HttpServletRequest r, T data) {
        return new ApiResponse<>((String) r.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE), data);
    }

    private static EngineeringBaselineService.Draft draft(
            MultipartFile file, String label, String note, String expectedJava) throws IOException {
        if (file.getSize() > 6356992) throw new IllegalArgumentException("工程原件超过 6 MiB 包限制。");
        return new EngineeringBaselineService.Draft(
                file.getOriginalFilename(), file.getBytes(), label, note, expectedJava);
    }

    @GetMapping
    public ApiResponse<List<Summary>> list(@PathVariable UUID system, HttpServletRequest r) {
        return ok(r, service.list(CurrentAccount.actor(r), system));
    }

    @GetMapping("/{id}")
    public ApiResponse<Snapshot> detail(
            @PathVariable UUID system, @PathVariable UUID id, HttpServletRequest r) {
        return ok(r, service.detail(CurrentAccount.actor(r), system, id));
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<EngineeringBaselineService.Preview> preview(
            @PathVariable UUID system,
            @RequestParam MultipartFile file,
            @RequestParam(defaultValue = "") String label,
            @RequestParam(defaultValue = "") String note,
            @RequestParam(defaultValue = "") String expectedJava,
            HttpServletRequest r)
            throws IOException {
        return ok(
                r,
                service.preview(
                        CurrentAccount.actor(r), system, draft(file, label, note, expectedJava)));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Snapshot> save(
            @PathVariable UUID system,
            @RequestParam MultipartFile file,
            @RequestParam UUID requestId,
            @RequestParam String token,
            @RequestParam(defaultValue = "") String label,
            @RequestParam(defaultValue = "") String note,
            @RequestParam(defaultValue = "") String expectedJava,
            HttpServletRequest r)
            throws IOException {
        return ok(
                r,
                service.save(
                        CurrentAccount.actor(r),
                        system,
                        requestId,
                        token,
                        draft(file, label, note, expectedJava)));
    }

    @GetMapping("/{id}/original")
    public ResponseEntity<byte[]> original(
            @PathVariable UUID system,
            @PathVariable UUID id,
            @RequestParam(required = false) String path,
            HttpServletRequest r) {
        var file = service.download(CurrentAccount.actor(r), system, id, path);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.name(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(file.bytes());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ProblemDetail invalid(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
