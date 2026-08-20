package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.knowledge.application.KnowledgeUploadStore;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import com.jundaodsj.insightops.server.knowledge.KnowledgeUploadService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/knowledge/uploads")
public class KnowledgeUploadController {
    private final KnowledgeUploadService service;

    public KnowledgeUploadController(KnowledgeUploadService service) { this.service = service; }

    @GetMapping
    public ApiResponse<List<KnowledgeUploadStore.UploadRecord>> list(HttpServletRequest request) {
        return new ApiResponse<>((String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE),
                service.list(CurrentAccount.account(request)));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<KnowledgeUploadStore.UploadRecord> upload(
            @RequestParam UUID projectId,
            @RequestParam(defaultValue = "PRIVATE") String visibility,
            @RequestParam MultipartFile file,
            HttpServletRequest request) {
        return new ApiResponse<>((String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE),
                service.upload(CurrentAccount.account(request), projectId, visibility, file));
    }

    @PostMapping("/{uploadId}/retry")
    public void retry(@PathVariable UUID uploadId, HttpServletRequest request) {
        service.retry(CurrentAccount.account(request), uploadId);
    }

    @DeleteMapping("/{uploadId}")
    public void delete(@PathVariable UUID uploadId, HttpServletRequest request) {
        service.delete(CurrentAccount.account(request), uploadId);
    }

    @GetMapping("/{uploadId}/content")
    public ResponseEntity<InputStreamResource> download(@PathVariable UUID uploadId,
                                                        HttpServletRequest request) {
        var download = service.download(CurrentAccount.account(request), uploadId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.mediaType()))
                .contentLength(download.byteSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(new InputStreamResource(download.input()));
    }
}
