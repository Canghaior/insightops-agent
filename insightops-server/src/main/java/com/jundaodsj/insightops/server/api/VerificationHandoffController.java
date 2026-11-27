package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.infrastructure.report.TemporaryReportArchive;
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
        send(response, "insightops-verification-" + versionId + ".zip", () -> service.prepare(actor, reportId, versionId));
    }

    @GetMapping("/api/v1/reports/{reportId}/tasks/versions/{taskVersionId}/execution.zip")
    public void execution(@PathVariable UUID reportId, @PathVariable UUID taskVersionId,
                          HttpServletRequest request, HttpServletResponse response) throws IOException {
        var actor = CurrentAccount.actor(request);
        send(response, "insightops-task-" + taskVersionId + ".zip", () -> service.prepareExecution(actor, reportId, taskVersionId));
    }

    @FunctionalInterface
    private interface PendingArchive { TemporaryReportArchive prepare() throws IOException; }

    private void send(HttpServletResponse response, String filename, PendingArchive pending) throws IOException {
        if (!downloads.tryAcquire())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "已有两个交接包正在生成或下载，请稍后重试。");
        try (var archive = pending.prepare()) {
            response.setContentType("application/zip");
            response.setContentLengthLong(archive.size());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename(filename).build().toString());
            archive.copyTo(response.getOutputStream());
        } finally {
            downloads.release();
        }
    }
}
