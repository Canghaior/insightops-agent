package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.project.application.UserProjectWatchStore;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects")
public class UserProjectWatchController {
    private final UserProjectWatchStore store;
    public UserProjectWatchController(UserProjectWatchStore store) { this.store = store; }

    @GetMapping
    public ApiResponse<List<UserProjectWatchStore.ProjectWatch>> list(HttpServletRequest request) {
        return new ApiResponse<>(traceId(request), store.list(CurrentAccount.actor(request)));
    }

    @PatchMapping("/{projectId}/watch")
    public ApiResponse<UserProjectWatchStore.ProjectWatch> watch(
            @PathVariable UUID projectId, @Valid @RequestBody WatchRequest body,
            HttpServletRequest request) {
        var project = store.setEnabled(
                        CurrentAccount.actor(request), projectId, body.enabled(), Instant.now())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        return new ApiResponse<>(traceId(request), project);
    }

    private static String traceId(HttpServletRequest request) {
        return (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
    }
    public record WatchRequest(@NotNull Boolean enabled) {}
}
