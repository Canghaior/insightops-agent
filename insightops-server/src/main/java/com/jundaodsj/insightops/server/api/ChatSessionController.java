package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.conversation.application.ChatRunStore;
import com.jundaodsj.insightops.conversation.application.ConversationManager;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.time.Instant;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/chat/sessions")
public class ChatSessionController {

    private final ChatRunStore chatRunStore;
    private final ConversationManager conversationManager;

    public ChatSessionController(ChatRunStore chatRunStore, ConversationManager conversationManager) {
        this.chatRunStore = chatRunStore;
        this.conversationManager = conversationManager;
    }

    @GetMapping
    public ApiResponse<List<ConversationManager.ConversationSummary>> list(
            @RequestParam(defaultValue = "false") boolean includeArchived,
            HttpServletRequest request) {
        return new ApiResponse<>(traceId(request), conversationManager.list(
                CurrentAccount.actor(request), includeArchived));
    }

    @GetMapping("/{sessionId}/messages")
    public ApiResponse<ChatRunStore.SessionHistory> messages(
            @PathVariable UUID sessionId,
            @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit,
            HttpServletRequest request) {
        ChatRunStore.SessionHistory history = chatRunStore.sessionHistory(
                        CurrentAccount.actor(request), sessionId, limit)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Conversation session not found"));
        return new ApiResponse<>(
                traceId(request),
                history);
    }

    @PatchMapping("/{sessionId}")
    public ApiResponse<ConversationManager.ConversationSummary> update(
            @PathVariable UUID sessionId,
            @Valid @RequestBody UpdateSessionRequest body,
            HttpServletRequest request) {
        var actor = CurrentAccount.actor(request);
        ConversationManager.ConversationSummary summary;
        if (body.title() != null) {
            summary = conversationManager.rename(actor, sessionId, body.title().trim(), Instant.now())
                    .orElseThrow(() -> notFound());
        }
        else if (body.archived() != null) {
            summary = conversationManager.archive(actor, sessionId, body.archived(), Instant.now())
                    .orElseThrow(() -> notFound());
        }
        else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title or archived is required");
        }
        return new ApiResponse<>(traceId(request), summary);
    }

    @DeleteMapping("/{sessionId}")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID sessionId, HttpServletRequest request) {
        if (!conversationManager.delete(CurrentAccount.actor(request), sessionId)) {
            throw notFound();
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation session not found");
    }

    private static String traceId(HttpServletRequest request) {
        return (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
    }

    public record UpdateSessionRequest(
            @Size(min = 1, max = 120) String title,
            Boolean archived) {
    }
}
