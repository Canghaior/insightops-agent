package com.jundaodsj.insightops.server.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/identity/exports")
public class PersonalDataExportController {
    private final PersonalDataExportService service;
    public PersonalDataExportController(PersonalDataExportService service) { this.service = service; }

    @PostMapping
    public PersonalDataExportService.ExportCreated create(HttpServletRequest request) {
        return service.create(CurrentAccount.account(request).userId());
    }

    @PostMapping("/{exportId}/download")
    public ResponseEntity<byte[]> download(@PathVariable UUID exportId,
                                           @Valid @RequestBody DownloadRequest body,
                                           HttpServletRequest request) {
        byte[] payload = service.download(CurrentAccount.account(request).userId(), exportId, body.token());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "no-store, max-age=0")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("insightops-personal-data.json", StandardCharsets.UTF_8).build().toString())
                .body(payload);
    }

    public record DownloadRequest(@NotBlank @Size(min=32,max=200) String token) { }
}
