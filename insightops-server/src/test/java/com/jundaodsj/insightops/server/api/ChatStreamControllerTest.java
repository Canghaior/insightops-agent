package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.conversation.application.ChatRunStore;
import com.jundaodsj.insightops.infrastructure.config.DeepSeekModelProperties;
import com.jundaodsj.insightops.model.application.ChatStreamEvent;
import com.jundaodsj.insightops.model.application.ChatStreamSession;
import com.jundaodsj.insightops.model.application.ModelCallErrorCode;
import com.jundaodsj.insightops.model.application.ModelCallException;
import com.jundaodsj.insightops.model.application.ModelUsage;
import com.jundaodsj.insightops.server.chat.ChatStreamSessionRegistry;
import com.jundaodsj.insightops.server.chat.ReleaseToolService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatStreamControllerTest {

    @Test
    void shouldCompleteStreamAndReleaseRegistryEntry() {
        ChatStreamSessionRegistry registry = new ChatStreamSessionRegistry();
        RecordingChatRunStore store = new RecordingChatRunStore();
        ChatStreamController controller = new ChatStreamController(
                (request, listener) -> {
                    assertThat(request.userPrompt()).isEqualTo("解释 Spring AI");
                    listener.onEvent(ChatStreamEvent.delta("Spring AI"));
                    listener.onEvent(ChatStreamEvent.completed(
                            "deepseek", "deepseek-v4-flash",
                            new ModelUsage(10, 5, 15, 0L, 0L),
                            Duration.ofSeconds(1), Duration.ofMillis(200)));
                    return session(new AtomicBoolean());
                },
                registry,
                properties(),
                store,
                noTool());

        var emitter = controller.stream(
                new ChatStreamController.ChatStreamRequest("解释 Spring AI"),
                request("trace-stream"));

        assertThat(emitter).isNotNull();
        assertThat(registry.activeCount()).isZero();
        assertThat(store.status).isEqualTo("SUCCEEDED");
        assertThat(store.answer).isEqualTo("Spring AI");
    }

    @Test
    void shouldCancelActiveStream() {
        ChatStreamSessionRegistry registry = new ChatStreamSessionRegistry();
        AtomicBoolean cancelled = new AtomicBoolean();
        RecordingChatRunStore store = new RecordingChatRunStore();
        ChatStreamController controller = new ChatStreamController(
                (request, listener) -> session(cancelled),
                registry,
                properties(),
                store,
                noTool());
        controller.stream(
                new ChatStreamController.ChatStreamRequest("长回答"),
                request("trace-start"));

        assertThat(registry.activeCount()).isEqualTo(1);
        var response = controller.cancel(store.runId.toString(), request("trace-cancel"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(cancelled).isTrue();
        assertThat(store.status).isEqualTo("CANCELLED");
    }

    @Test
    void shouldPersistFailedRunWhenProviderFails() {
        ChatStreamSessionRegistry registry = new ChatStreamSessionRegistry();
        RecordingChatRunStore store = new RecordingChatRunStore();
        ChatStreamController controller = new ChatStreamController(
                (request, listener) -> {
                    listener.onError(new ModelCallException(
                            ModelCallErrorCode.PROVIDER_ERROR,
                            "deepseek",
                            new IllegalStateException("provider unavailable")));
                    return session(new AtomicBoolean());
                },
                registry,
                properties(),
                store,
                noTool());

        controller.stream(
                new ChatStreamController.ChatStreamRequest("解释 Agent"),
                request("trace-failed"));

        assertThat(store.status).isEqualTo("FAILED");
        assertThat(store.failureCode).isEqualTo("PROVIDER_ERROR");
        assertThat(registry.activeCount()).isZero();
    }

    private static DeepSeekModelProperties properties() {
        return new DeepSeekModelProperties(
                true, "https://api.deepseek.com", "deepseek-v4-flash", false,
                0.2, 4096, 4, 90, 2, false);
    }

    private static MockHttpServletRequest request(String traceId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE, traceId);
        return request;
    }

    private static ChatStreamSession session(AtomicBoolean cancelled) {
        return new ChatStreamSession() {
            @Override
            public void cancel() {
                cancelled.set(true);
            }

            @Override
            public boolean cancelled() {
                return cancelled.get();
            }
        };
    }

    private static ReleaseToolService noTool() {
        ReleaseToolService service = mock(ReleaseToolService.class);
        when(service.execute(any(), anyString(), any())).thenReturn(Optional.empty());
        return service;
    }

    private static final class RecordingChatRunStore implements ChatRunStore {

        private final UUID sessionId = UUID.randomUUID();
        private UUID runId;
        private String status;
        private String answer;
        private String failureCode;

        @Override
        public UUID startRun(
                UUID runId,
                UUID requestedSessionId,
                String traceId,
                String question,
                Instant startedAt) {
            this.runId = runId;
            this.status = "RUNNING";
            return requestedSessionId == null ? sessionId : requestedSessionId;
        }

        @Override
        public void succeedRun(
                UUID runId,
                String answer,
                String provider,
                String model,
                ModelUsage usage,
                List<String> citations,
                Instant finishedAt) {
            this.status = "SUCCEEDED";
            this.answer = answer;
        }

        @Override
        public void cancelRun(UUID runId, String partialAnswer, Instant finishedAt) {
            this.status = "CANCELLED";
            this.answer = partialAnswer;
        }

        @Override
        public void failRun(
                UUID runId,
                String partialAnswer,
                String failureCode,
                Instant finishedAt) {
            this.status = "FAILED";
            this.answer = partialAnswer;
            this.failureCode = failureCode;
        }
    }
}
