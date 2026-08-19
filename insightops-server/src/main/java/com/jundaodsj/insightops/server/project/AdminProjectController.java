package com.jundaodsj.insightops.server.project;

import com.jundaodsj.insightops.project.application.AdminProjectStore;
import com.jundaodsj.insightops.server.api.ApiResponse;
import com.jundaodsj.insightops.server.api.TraceIdFilter;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/projects")
public class AdminProjectController {

    private final AdminProjectService service;

    public AdminProjectController(AdminProjectService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<AdminProjectStore.ManagedProject>> list(HttpServletRequest request) {
        return response(request, service.list(CurrentAccount.account(request)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdminProjectStore.ManagedProject> create(
            @Valid @RequestBody ProjectRequest body,
            HttpServletRequest request) {
        return response(request, service.create(
                CurrentAccount.account(request),
                body.repositoryOwner(),
                body.repositoryName(),
                body.priority()));
    }

    @PutMapping("/{projectId}")
    public ApiResponse<AdminProjectStore.ManagedProject> update(
            @PathVariable UUID projectId,
            @Valid @RequestBody ProjectRequest body,
            HttpServletRequest request) {
        return response(request, service.update(
                CurrentAccount.account(request),
                projectId,
                body.repositoryOwner(),
                body.repositoryName(),
                body.priority()));
    }

    @PatchMapping("/{projectId}/status")
    public ApiResponse<AdminProjectStore.ManagedProject> status(
            @PathVariable UUID projectId,
            @Valid @RequestBody StatusRequest body,
            HttpServletRequest request) {
        return response(request, service.setEnabled(
                CurrentAccount.account(request), projectId, body.enabled()));
    }

    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID projectId, HttpServletRequest request) {
        service.delete(CurrentAccount.account(request), projectId);
    }

    private static <T> ApiResponse<T> response(HttpServletRequest request, T data) {
        return new ApiResponse<>((String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE), data);
    }

    public record ProjectRequest(
            @NotBlank @Size(max = 39) String repositoryOwner,
            @NotBlank @Size(max = 100) String repositoryName,
            @Min(1) @Max(5) int priority) {
    }

    public record StatusRequest(@NotNull Boolean enabled) {
    }
}
