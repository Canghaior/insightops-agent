package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.project.application.ProjectUpdateStore;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/updates")
public class ProjectUpdateController {
    private final ProjectUpdateStore store;

    public ProjectUpdateController(ProjectUpdateStore store) {
        this.store = store;
    }

    @GetMapping
    public ApiResponse<ProjectUpdateStore.UpdatePage> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String riskLevel,
            @RequestParam(defaultValue = "false") boolean matchedOnly,
            HttpServletRequest request) {
        return response(request, store.listUpdates(
                CurrentAccount.actor(request), page, size, projectId, unreadOnly,
                eventType, riskLevel, matchedOnly));
    }

    @GetMapping("/unread-count")
    public ApiResponse<UnreadCount> unreadCount(HttpServletRequest request) {
        return response(request, new UnreadCount(store.unreadCount(CurrentAccount.actor(request))));
    }

    @PostMapping("/{eventId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@PathVariable UUID eventId, HttpServletRequest request) {
        if (!store.markRead(CurrentAccount.actor(request), eventId, Instant.now())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Update not found in watched projects");
        }
    }

    @PostMapping("/read-all")
    public ApiResponse<MarkedRead> markAllRead(HttpServletRequest request) {
        return response(request, new MarkedRead(store.markAllRead(CurrentAccount.actor(request), Instant.now())));
    }

    private static <T> ApiResponse<T> response(HttpServletRequest request, T data) {
        return new ApiResponse<>((String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE), data);
    }

    public record UnreadCount(long count) {
    }

    public record MarkedRead(int count) {
    }
}
