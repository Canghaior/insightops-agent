package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.project.application.ProjectUpdateStore;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/collection")
public class CollectionAdminController {
    private final ProjectUpdateStore store;

    public CollectionAdminController(ProjectUpdateStore store) {
        this.store = store;
    }

    @GetMapping
    public ApiResponse<List<ProjectUpdateStore.CollectionStatus>> status(HttpServletRequest request) {
        var account = requireSystemAdmin(request);
        return response(request, store.collectionStatus(account.workspaceId()));
    }

    @PostMapping("/{projectId}/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void sync(@PathVariable UUID projectId, HttpServletRequest request) {
        var account = requireSystemAdmin(request);
        if (!store.requestSync(account.workspaceId(), projectId, Instant.now())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tracked project not found");
        }
    }

    private static com.jundaodsj.insightops.identity.application.AccountWorkspaceStore.AccountRecord
    requireSystemAdmin(HttpServletRequest request) {
        var account = CurrentAccount.account(request);
        if (!"SYSTEM_ADMIN".equals(account.systemRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Collection administration requires a system administrator");
        }
        return account;
    }

    private static <T> ApiResponse<T> response(HttpServletRequest request, T data) {
        return new ApiResponse<>((String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE), data);
    }
}
