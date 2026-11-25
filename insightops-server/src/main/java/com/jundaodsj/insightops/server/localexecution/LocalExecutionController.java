package com.jundaodsj.insightops.server.localexecution;

import com.jundaodsj.insightops.server.api.ApiResponse;
import com.jundaodsj.insightops.server.api.TraceIdFilter;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reports/{reportId}/tasks/versions/{taskVersionId}/local-execution")
class LocalExecutionController {
    private final ObjectProvider<LocalEngineeringRuns> services;

    LocalExecutionController(ObjectProvider<LocalEngineeringRuns> services) { this.services = services; }

    @GetMapping
    ApiResponse<LocalEngineeringRuns.Overview> overview(@PathVariable UUID reportId, @PathVariable UUID taskVersionId,
                                                        HttpServletRequest request) throws IOException {
        var service = services.getIfAvailable();
        return response(request, service != null && operator(request)
                ? service.overview(CurrentAccount.actor(request), reportId, taskVersionId)
                : LocalEngineeringRuns.Overview.disabled());
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<LocalRunStore.Run> start(@PathVariable UUID reportId, @PathVariable UUID taskVersionId,
                                       @RequestBody LocalRunStore.Command command, HttpServletRequest request) throws IOException {
        return response(request, require(request).start(CurrentAccount.actor(request), reportId, taskVersionId, command));
    }

    @GetMapping("/runs/{runId}")
    ApiResponse<LocalRunStore.Detail> detail(@PathVariable UUID reportId, @PathVariable UUID taskVersionId,
                                           @PathVariable UUID runId, HttpServletRequest request) {
        return response(request, require(request).detail(CurrentAccount.actor(request), reportId, taskVersionId, runId));
    }

    @PostMapping("/runs/{runId}/cancel")
    ApiResponse<LocalRunStore.Run> cancel(@PathVariable UUID reportId, @PathVariable UUID taskVersionId,
                                        @PathVariable UUID runId, HttpServletRequest request) {
        return response(request, require(request).cancel(CurrentAccount.actor(request), reportId, taskVersionId, runId));
    }

    @GetMapping("/runs/{runId}/patch")
    ResponseEntity<byte[]> patch(@PathVariable UUID reportId, @PathVariable UUID taskVersionId, @PathVariable UUID runId,
                                 HttpServletRequest request) throws IOException {
        byte[] bytes = require(request).patch(CurrentAccount.actor(request), reportId, taskVersionId, runId);
        return ResponseEntity.ok().contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store").header("X-Content-Type-Options", "nosniff").body(bytes);
    }

    @GetMapping("/runs/{runId}/evidence.zip")
    ResponseEntity<byte[]> evidence(@PathVariable UUID reportId, @PathVariable UUID taskVersionId, @PathVariable UUID runId,
                                    HttpServletRequest request) throws IOException {
        byte[] bytes = require(request).evidence(CurrentAccount.actor(request), reportId, taskVersionId, runId);
        var disposition = ContentDisposition.attachment().filename("insightops-local-execution-" + runId + ".zip").build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip")).contentLength(bytes.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString()).header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff").body(bytes);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ProblemDetail invalid(IllegalArgumentException cause) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, cause.getMessage());
    }

    @ExceptionHandler(IOException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ProblemDetail unavailable(IOException cause) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "无法读取已登记仓库或本地执行原件。请刷新后重试。");
    }

    private LocalEngineeringRuns require(HttpServletRequest request) {
        var service = services.getIfAvailable();
        if (service == null || !operator(request)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "本地执行入口未启用。");
        return service;
    }

    private static boolean operator(HttpServletRequest request) {
        var account = CurrentAccount.account(request);
        return "OWNER".equals(account.role()) || "SYSTEM_ADMIN".equals(account.systemRole());
    }

    private static <T> ApiResponse<T> response(HttpServletRequest request, T data) {
        return new ApiResponse<>((String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE), data);
    }
}
