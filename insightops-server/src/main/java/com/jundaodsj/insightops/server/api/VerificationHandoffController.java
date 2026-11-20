package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.server.auth.CurrentAccount;
import com.jundaodsj.insightops.server.report.VerificationHandoffService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Semaphore;

@RestController
public class VerificationHandoffController {
    private final VerificationHandoffService service;
    // Each export can hold a bounded archive on disk until the client finishes downloading it.
    private final Semaphore downloads = new Semaphore(2);

    public VerificationHandoffController(VerificationHandoffService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/reports/{reportId}/review/verifications/{versionId}/handoff.zip")
    public void download(@PathVariable UUID reportId, @PathVariable UUID versionId,
                         HttpServletRequest request, HttpServletResponse response) throws IOException {
        var actor = CurrentAccount.actor(request);
        if (!downloads.tryAcquire())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "已有两个交接包正在生成或下载，请稍后重试。");
        try (var archive = service.prepare(actor, reportId, versionId)) {
            response.setContentType("application/zip");
            response.setContentLengthLong(archive.size());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename("insightops-verification-" + versionId + ".zip").build().toString());
            archive.copyTo(response.getOutputStream());
        } finally {
            downloads.release();
        }
    }
}
