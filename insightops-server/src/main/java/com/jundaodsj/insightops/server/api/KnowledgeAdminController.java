package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import com.jundaodsj.insightops.knowledge.application.KnowledgeStore;
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
@RequestMapping("/api/v1/admin/knowledge")
public class KnowledgeAdminController {
    private final KnowledgeStore store;

    public KnowledgeAdminController(KnowledgeStore store) {
        this.store = store;
    }

    @GetMapping("/sources")
    public ApiResponse<List<KnowledgeStore.SourceStatus>> sources(HttpServletRequest request) {
        var account = requireSystemAdmin(request);
        return new ApiResponse<>((String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE),
                store.sourceStatus(account.workspaceId()));
    }

    @PostMapping("/sources/{sourceId}/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void sync(@PathVariable UUID sourceId, HttpServletRequest request) {
        var account = requireSystemAdmin(request);
        if (!store.requestSync(account.workspaceId(), sourceId, Instant.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Knowledge source was not found, disabled, or is already running");
        }
    }

    private static AccountWorkspaceStore.AccountRecord requireSystemAdmin(HttpServletRequest request) {
        var account = CurrentAccount.account(request);
        if (!"SYSTEM_ADMIN".equals(account.systemRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Knowledge administration requires a system administrator");
        }
        return account;
    }
}
