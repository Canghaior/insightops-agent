package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.conversation.application.ChatRunStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/chat/sessions")
public class ChatSessionController {

    private final ChatRunStore chatRunStore;

    public ChatSessionController(ChatRunStore chatRunStore) {
        this.chatRunStore = chatRunStore;
    }

    @GetMapping("/{sessionId}/messages")
    public ApiResponse<ChatRunStore.SessionHistory> messages(
            @PathVariable UUID sessionId,
            @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit,
            HttpServletRequest request) {
        ChatRunStore.SessionHistory history = chatRunStore.sessionHistory(sessionId, limit)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Conversation session not found"));
        return new ApiResponse<>(
                (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE),
                history);
    }
}
