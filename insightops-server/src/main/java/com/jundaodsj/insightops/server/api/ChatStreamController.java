package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.infrastructure.config.DeepSeekModelProperties;
import com.jundaodsj.insightops.conversation.application.ChatRunStore;
import com.jundaodsj.insightops.model.application.ChatModelRequest;
import com.jundaodsj.insightops.model.application.ChatStreamEvent;
import com.jundaodsj.insightops.model.application.ChatStreamEventType;
import com.jundaodsj.insightops.model.application.ChatStreamListener;
import com.jundaodsj.insightops.model.application.ModelCallException;
import com.jundaodsj.insightops.model.application.ModelUsage;
import com.jundaodsj.insightops.model.application.StreamingChatModelGateway;
import com.jundaodsj.insightops.server.chat.ChatStreamSessionRegistry;
import com.jundaodsj.insightops.server.chat.P0ChatGuardrail;
import com.jundaodsj.insightops.server.chat.ReleaseToolService;
import com.jundaodsj.insightops.tool.application.github.GitHubToolErrorCode;
import com.jundaodsj.insightops.tool.application.github.GitHubToolException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api/v1/chat/streams")
@ConditionalOnProperty(prefix = "insightops.model.deepseek", name = "enabled", havingValue = "true")
public class ChatStreamController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatStreamController.class);

    private static final String SYSTEM_PROMPT = """
            你是 InsightOps Agent，面向 Java 开发者、架构师和技术负责人回答 AI 开源项目问题。
            当前 P0 只启用 GitHub Release 数据源，不得声称查询了 Issue、PR、Roadmap、文档或其他来源。
            如果系统提示中附有工具证据，只能基于该证据回答实时版本事实，并为关键事实保留官方 Release URL。
            如果没有工具证据，不要编造实时版本、发布日期或来源链接；其他问题使用中文清晰、简洁地回答。
            """;

    private final StreamingChatModelGateway streamingGateway;
    private final ChatStreamSessionRegistry sessionRegistry;
    private final DeepSeekModelProperties modelProperties;
    private final ChatRunStore chatRunStore;
    private final ReleaseToolService releaseToolService;
    private final P0ChatGuardrail guardrail;

    public ChatStreamController(
            StreamingChatModelGateway streamingGateway,
            ChatStreamSessionRegistry sessionRegistry,
            DeepSeekModelProperties modelProperties,
            ChatRunStore chatRunStore,
            ReleaseToolService releaseToolService,
            P0ChatGuardrail guardrail) {
        this.streamingGateway = streamingGateway;
        this.sessionRegistry = sessionRegistry;
        this.modelProperties = modelProperties;
        this.chatRunStore = chatRunStore;
        this.releaseToolService = releaseToolService;
        this.guardrail = guardrail;
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @Valid @RequestBody ChatStreamRequest body,
            HttpServletRequest request) {
        UUID runUuid = UUID.randomUUID();
        String runId = runUuid.toString();
        String traceId = (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        Instant startedAt = Instant.now();
        String userMessage;
        try {
            userMessage = guardrail.normalizeInput(body.message());
        }
        catch (P0ChatGuardrail.GuardrailViolation exception) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Chat input rejected by safety policy");
        }
        List<ChatRunStore.StoredMessage> history = body.sessionId() == null
                ? List.of()
                : chatRunStore.recentMessages(body.sessionId(), 12);
        UUID sessionId = chatRunStore.startRun(
                runUuid,
                body.sessionId(),
                traceId,
                userMessage,
                startedAt);
        AtomicLong sequence = new AtomicLong();
        StringBuffer answer = new StringBuffer();
        SseEmitter emitter = new SseEmitter(modelProperties.requestTimeoutSeconds() * 1_000L);

        sessionRegistry.register(runId, () -> {
            try {
                chatRunStore.cancelRun(runUuid, answer.toString(), Instant.now());
            }
            catch (RuntimeException exception) {
                LOGGER.error("Failed to persist cancellation for run {}", runId, exception);
            }
            finally {
                send(emitter, ChatSseEvent.cancelled(
                        runId, sessionId, sequence.incrementAndGet(), traceId));
                emitter.complete();
            }
        });
        emitter.onCompletion(() -> cancelDisconnectedRun(runUuid, runId, answer));
        emitter.onError(error -> cancelDisconnectedRun(runUuid, runId, answer));
        emitter.onTimeout(() -> {
            if (sessionRegistry.disconnect(runId)) {
                failRunSafely(runUuid, answer, "TIMED_OUT");
                send(emitter, ChatSseEvent.error(
                        runId,
                        sessionId,
                        sequence.incrementAndGet(),
                        traceId,
                        "TIMED_OUT"));
            }
            emitter.complete();
        });

        if (!send(emitter, ChatSseEvent.started(
                runId, sessionId, sequence.incrementAndGet(), traceId))) {
            cancelDisconnectedRun(runUuid, runId, answer);
            return emitter;
        }

        Optional<ReleaseToolService.ToolEvidence> toolEvidence;
        try {
            toolEvidence = releaseToolService.execute(
                    runUuid,
                    userMessage,
                    previousUserQuestions(history),
                    new ReleaseToolService.ToolProgressListener() {
                        @Override
                        public void onStarted(UUID toolCallId, String toolName) {
                            if (!send(emitter, ChatSseEvent.toolStarted(
                                    runId,
                                    sessionId,
                                    sequence.incrementAndGet(),
                                    traceId,
                                    toolCallId,
                                    toolName))) {
                                cancelDisconnectedRun(runUuid, runId, answer);
                            }
                        }

                        @Override
                        public void onCompleted(
                                UUID toolCallId,
                                String toolName,
                                int releaseCount) {
                            if (!send(emitter, ChatSseEvent.toolCompleted(
                                    runId,
                                    sessionId,
                                    sequence.incrementAndGet(),
                                    traceId,
                                    toolCallId,
                                    toolName,
                                    releaseCount))) {
                                cancelDisconnectedRun(runUuid, runId, answer);
                            }
                        }
                    });
        }
        catch (GitHubToolException exception) {
            String errorCode = toolErrorCode(exception.code());
            failRunSafely(runUuid, answer, errorCode);
            send(emitter, ChatSseEvent.error(
                    runId,
                    sessionId,
                    sequence.incrementAndGet(),
                    traceId,
                    errorCode));
            sessionRegistry.complete(runId);
            emitter.complete();
            return emitter;
        }
        if (!sessionRegistry.isActive(runId)) {
            return emitter;
        }

        String systemPrompt = SYSTEM_PROMPT + guardrail.systemPolicy() + toolEvidence
                .map(ReleaseToolService.ToolEvidence::systemPromptAppendix)
                .orElse("");
        List<String> citations = toolEvidence
                .map(ReleaseToolService.ToolEvidence::sourceUrls)
                .orElseGet(List::of);
        try {
            guardrail.verifyTrustedReleaseSources(citations);
        }
        catch (P0ChatGuardrail.GuardrailViolation exception) {
            failRunSafely(runUuid, answer, exception.code());
            send(emitter, ChatSseEvent.error(
                    runId,
                    sessionId,
                    sequence.incrementAndGet(),
                    traceId,
                    exception.code()));
            sessionRegistry.complete(runId);
            emitter.complete();
            return emitter;
        }

        try {
            var session = streamingGateway.stream(
                    new ChatModelRequest(
                            systemPrompt,
                            guardrail.contextualUserPrompt(history, userMessage),
                            modelProperties.temperature(),
                            modelProperties.maxOutputTokens()),
                    new ChatStreamListener() {
                        @Override
                        public void onEvent(ChatStreamEvent event) {
                            if (event.type() == ChatStreamEventType.CONTENT_DELTA) {
                                answer.append(event.content());
                                if (!send(emitter, ChatSseEvent.delta(
                                        runId,
                                        sessionId,
                                        sequence.incrementAndGet(),
                                        traceId,
                                        event.content()))) {
                                    cancelDisconnectedRun(runUuid, runId, answer);
                                }
                                return;
                            }
                            try {
                                chatRunStore.succeedRun(
                                        runUuid,
                                        answer.toString(),
                                        event.provider(),
                                        event.model(),
                                        event.usage(),
                                        citations,
                                        Instant.now());
                            }
                            catch (RuntimeException exception) {
                                LOGGER.error("Failed to persist successful run {}", runId, exception);
                                failRunSafely(runUuid, answer, "PERSISTENCE_ERROR");
                                send(emitter, ChatSseEvent.error(
                                        runId,
                                        sessionId,
                                        sequence.incrementAndGet(),
                                        traceId,
                                        "PERSISTENCE_ERROR"));
                                sessionRegistry.complete(runId);
                                emitter.complete();
                                return;
                            }
                            send(emitter, ChatSseEvent.completed(
                                    runId,
                                    sessionId,
                                    sequence.incrementAndGet(),
                                    traceId,
                                    event,
                                    citations));
                            sessionRegistry.complete(runId);
                            emitter.complete();
                        }

                        @Override
                        public void onError(ModelCallException exception) {
                            failRunSafely(runUuid, answer, exception.code().name());
                            send(emitter, ChatSseEvent.error(
                                    runId,
                                    sessionId,
                                    sequence.incrementAndGet(),
                                    traceId,
                                    exception.code().name()));
                            sessionRegistry.complete(runId);
                            emitter.complete();
                        }
                    });
            sessionRegistry.attach(runId, session);
        }
        catch (ModelCallException exception) {
            failRunSafely(runUuid, answer, exception.code().name());
            send(emitter, ChatSseEvent.error(
                    runId,
                    sessionId,
                    sequence.incrementAndGet(),
                    traceId,
                    exception.code().name()));
            sessionRegistry.complete(runId);
            emitter.complete();
        }
        return emitter;
    }

    private static String previousUserQuestions(List<ChatRunStore.StoredMessage> history) {
        return history.stream()
                .filter(message -> "USER".equals(message.role()))
                .map(ChatRunStore.StoredMessage::content)
                .reduce((ignored, latest) -> latest)
                .orElse("");
    }

    private String toolErrorCode(GitHubToolErrorCode code) {
        return switch (code) {
            case RATE_LIMITED -> "TOOL_RATE_LIMITED";
            case TIMEOUT -> "TOOL_TIMEOUT";
            case VALIDATION_ERROR -> "TOOL_VALIDATION_ERROR";
            case TRANSIENT_REMOTE, INTERNAL_ERROR -> "TOOL_ERROR";
        };
    }

    private void cancelDisconnectedRun(UUID runUuid, String runId, StringBuffer answer) {
        if (!sessionRegistry.disconnect(runId)) {
            return;
        }
        try {
            chatRunStore.cancelRun(runUuid, answer.toString(), Instant.now());
        }
        catch (RuntimeException exception) {
            LOGGER.error("Failed to persist disconnected run {}", runId, exception);
        }
    }

    private void failRunSafely(UUID runId, StringBuffer answer, String failureCode) {
        try {
            chatRunStore.failRun(runId, answer.toString(), failureCode, Instant.now());
        }
        catch (RuntimeException exception) {
            LOGGER.error("Failed to persist failed run {}", runId, exception);
        }
    }

    @PostMapping("/{runId}/cancel")
    public ResponseEntity<ApiResponse<CancelStreamResult>> cancel(
            @PathVariable String runId,
            HttpServletRequest request) {
        boolean cancelled = sessionRegistry.cancel(runId);
        String traceId = (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        ApiResponse<CancelStreamResult> response = new ApiResponse<>(
                traceId,
                new CancelStreamResult(runId, cancelled));
        return new ResponseEntity<>(response, cancelled ? HttpStatus.OK : HttpStatus.NOT_FOUND);
    }

    private boolean send(SseEmitter emitter, ChatSseEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .id(Long.toString(event.sequence()))
                    .name(event.type())
                    .data(event));
            return true;
        }
        catch (IOException | IllegalStateException exception) {
            return false;
        }
    }

    public record ChatStreamRequest(
            @NotBlank(message = "message must not be blank")
            @Size(max = 4000, message = "message must not exceed 4000 characters")
            String message,
            UUID sessionId) {

        public ChatStreamRequest(String message) {
            this(message, null);
        }
    }

    public record CancelStreamResult(String runId, boolean cancelled) {
    }

    public record ChatSseEvent(
            String type,
            String runId,
            UUID sessionId,
            long sequence,
            Instant timestamp,
            String traceId,
            String content,
            String provider,
            String model,
            ModelUsage usage,
            Long durationMs,
            Long timeToFirstTokenMs,
            String errorCode,
            String toolName,
            UUID toolCallId,
            Integer releaseCount,
            List<String> sources) {

        static ChatSseEvent started(String runId, UUID sessionId, long sequence, String traceId) {
            return new ChatSseEvent(
                    "started", runId, sessionId, sequence, Instant.now(), traceId,
                    null, null, null, null, null, null, null,
                    null, null, null, List.of());
        }

        static ChatSseEvent delta(
                String runId, UUID sessionId, long sequence, String traceId, String content) {
            return new ChatSseEvent(
                    "delta", runId, sessionId, sequence, Instant.now(), traceId,
                    content, null, null, null, null, null, null,
                    null, null, null, List.of());
        }

        static ChatSseEvent completed(
                String runId,
                UUID sessionId,
                long sequence,
                String traceId,
                ChatStreamEvent event,
                List<String> sources) {
            return new ChatSseEvent(
                    "completed", runId, sessionId, sequence, Instant.now(), traceId,
                    null, event.provider(), event.model(), event.usage(),
                    event.duration().toMillis(),
                    event.timeToFirstToken() == null ? null : event.timeToFirstToken().toMillis(),
                    null, null, null, null, sources);
        }

        static ChatSseEvent cancelled(
                String runId, UUID sessionId, long sequence, String traceId) {
            return new ChatSseEvent(
                    "cancelled", runId, sessionId, sequence, Instant.now(), traceId,
                    null, null, null, null, null, null, null,
                    null, null, null, List.of());
        }

        static ChatSseEvent error(
                String runId, UUID sessionId, long sequence, String traceId, String errorCode) {
            return new ChatSseEvent(
                    "error", runId, sessionId, sequence, Instant.now(), traceId,
                    null, null, null, null, null, null, errorCode,
                    null, null, null, List.of());
        }

        static ChatSseEvent toolStarted(
                String runId,
                UUID sessionId,
                long sequence,
                String traceId,
                UUID toolCallId,
                String toolName) {
            return new ChatSseEvent(
                    "tool_started", runId, sessionId, sequence, Instant.now(), traceId,
                    null, null, null, null, null, null, null,
                    toolName, toolCallId, null, List.of());
        }

        static ChatSseEvent toolCompleted(
                String runId,
                UUID sessionId,
                long sequence,
                String traceId,
                UUID toolCallId,
                String toolName,
                int releaseCount) {
            return new ChatSseEvent(
                    "tool_completed", runId, sessionId, sequence, Instant.now(), traceId,
                    null, null, null, null, null, null, null,
                    toolName, toolCallId, releaseCount, List.of());
        }
    }
}
