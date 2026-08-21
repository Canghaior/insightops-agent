package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.infrastructure.config.DeepSeekModelProperties;
import com.jundaodsj.insightops.conversation.application.ChatRunStore;
import com.jundaodsj.insightops.conversation.application.ChatCitation;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.memory.application.UserMemoryStore;
import com.jundaodsj.insightops.model.application.ChatModelRequest;
import com.jundaodsj.insightops.model.application.ChatStreamEvent;
import com.jundaodsj.insightops.model.application.ChatStreamEventType;
import com.jundaodsj.insightops.model.application.ChatStreamListener;
import com.jundaodsj.insightops.model.application.ModelCallException;
import com.jundaodsj.insightops.model.application.ModelUsage;
import com.jundaodsj.insightops.model.application.StreamingChatModelGateway;
import com.jundaodsj.insightops.server.chat.AgentLoopService;
import com.jundaodsj.insightops.server.chat.ChatStreamSessionRegistry;
import com.jundaodsj.insightops.server.chat.KnowledgeRagService;
import com.jundaodsj.insightops.server.chat.P0ChatGuardrail;
import com.jundaodsj.insightops.server.chat.ProjectEventEvidenceService;
import com.jundaodsj.insightops.server.chat.ReleaseToolService;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import com.jundaodsj.insightops.tool.application.github.GitHubToolErrorCode;
import com.jundaodsj.insightops.tool.application.github.GitHubToolException;
import com.jundaodsj.insightops.tool.application.registry.AgentToolDefinition;
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
            当前可使用 GitHub Release、Issue、Pull Request、Security Advisory、官方文档、官方博客/RSS、Roadmap 与当前用户有权访问的上传资料。
            不得声称查询了系统证据中没有出现的来源。
            如果系统提示中附有工具或知识库证据，只能基于该证据回答可验证事实，并为关键事实保留 [S#] 引用或官方 URL。
            如果没有证据，不要编造实时版本、发布日期、接口能力或来源链接；其他问题使用中文清晰、简洁地回答。
            """;

    private final StreamingChatModelGateway streamingGateway;
    private final ChatStreamSessionRegistry sessionRegistry;
    private final DeepSeekModelProperties modelProperties;
    private final ChatRunStore chatRunStore;
    private final ReleaseToolService releaseToolService;
    private final KnowledgeRagService knowledgeRagService;
    private final ProjectEventEvidenceService projectEventEvidenceService;
    private final AgentLoopService agentLoopService;
    private final P0ChatGuardrail guardrail;
    private final UserMemoryStore userMemoryStore;

    @org.springframework.beans.factory.annotation.Autowired
    public ChatStreamController(
            StreamingChatModelGateway streamingGateway,
            ChatStreamSessionRegistry sessionRegistry,
            DeepSeekModelProperties modelProperties,
            ChatRunStore chatRunStore,
            ReleaseToolService releaseToolService,
            KnowledgeRagService knowledgeRagService,
            ProjectEventEvidenceService projectEventEvidenceService,
            AgentLoopService agentLoopService,
            P0ChatGuardrail guardrail,
            UserMemoryStore userMemoryStore) {
        this.streamingGateway = streamingGateway;
        this.sessionRegistry = sessionRegistry;
        this.modelProperties = modelProperties;
        this.chatRunStore = chatRunStore;
        this.releaseToolService = releaseToolService;
        this.knowledgeRagService = knowledgeRagService;
        this.projectEventEvidenceService = projectEventEvidenceService;
        this.agentLoopService = agentLoopService;
        this.guardrail = guardrail;
        this.userMemoryStore = userMemoryStore;
    }

    public ChatStreamController(
            StreamingChatModelGateway streamingGateway,
            ChatStreamSessionRegistry sessionRegistry,
            DeepSeekModelProperties modelProperties,
            ChatRunStore chatRunStore,
            ReleaseToolService releaseToolService,
            KnowledgeRagService knowledgeRagService,
            ProjectEventEvidenceService projectEventEvidenceService,
            P0ChatGuardrail guardrail,
            UserMemoryStore userMemoryStore) {
        this(streamingGateway, sessionRegistry, modelProperties, chatRunStore,
                releaseToolService, knowledgeRagService, projectEventEvidenceService,
                null, guardrail, userMemoryStore);
    }

    public ChatStreamController(
            StreamingChatModelGateway streamingGateway,
            ChatStreamSessionRegistry sessionRegistry,
            DeepSeekModelProperties modelProperties,
            ChatRunStore chatRunStore,
            ReleaseToolService releaseToolService,
            KnowledgeRagService knowledgeRagService,
            P0ChatGuardrail guardrail,
            UserMemoryStore userMemoryStore) {
        this(streamingGateway, sessionRegistry, modelProperties, chatRunStore,
                releaseToolService, knowledgeRagService, null, guardrail, userMemoryStore);
    }

    public ChatStreamController(
            StreamingChatModelGateway streamingGateway,
            ChatStreamSessionRegistry sessionRegistry,
            DeepSeekModelProperties modelProperties,
            ChatRunStore chatRunStore,
            ReleaseToolService releaseToolService,
            P0ChatGuardrail guardrail,
            UserMemoryStore userMemoryStore) {
        this(streamingGateway, sessionRegistry, modelProperties, chatRunStore,
                releaseToolService, null, guardrail, userMemoryStore);
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @Valid @RequestBody ChatStreamRequest body,
            HttpServletRequest request) {
        UUID runUuid = UUID.randomUUID();
        String runId = runUuid.toString();
        String traceId = (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        var account = CurrentAccount.account(request);
        ActorContext actor = account.actor();
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
                : chatRunStore.recentMessages(actor, body.sessionId(), 12);
        UUID sessionId = chatRunStore.startRun(
                actor,
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

        Optional<ReleaseToolService.ToolEvidence> toolEvidence = Optional.empty();
        Optional<KnowledgeRagService.RagEvidence> ragEvidence = Optional.empty();
        Optional<ProjectEventEvidenceService.EventEvidence> eventEvidence = Optional.empty();
        AgentLoopService.LoopResult loopResult = null;
        if (agentLoopService != null) {
            try {
                loopResult = agentLoopService.run(
                        new AgentLoopService.LoopRequest(
                                runUuid, actor.workspaceId(), actor.userId(),
                                "SYSTEM_ADMIN".equals(account.systemRole()),
                                toolAccess(account.systemRole(), account.role()),
                                guardrail.contextualUserPrompt(history, userMessage)),
                        new com.jundaodsj.insightops.server.chat.AgentToolDispatcher.ProgressListener() {
                            @Override
                            public void onStarted(UUID toolCallId, String toolName, int round) {
                                if (!send(emitter, ChatSseEvent.toolStarted(
                                        runId, sessionId, sequence.incrementAndGet(), traceId,
                                        toolCallId, toolName))) {
                                    cancelDisconnectedRun(runUuid, runId, answer);
                                }
                            }

                            @Override
                            public void onCompleted(UUID toolCallId, String toolName, int round,
                                                    int resultCount, String model) {
                                boolean sent = KnowledgeRagService.TOOL_NAME.equals(toolName)
                                        ? send(emitter, ChatSseEvent.retrievalCompleted(
                                        runId, sessionId, sequence.incrementAndGet(), traceId,
                                        toolCallId, toolName, resultCount, model))
                                        : send(emitter, ChatSseEvent.toolCompleted(
                                        runId, sessionId, sequence.incrementAndGet(), traceId,
                                        toolCallId, toolName, resultCount));
                                if (!sent) cancelDisconnectedRun(runUuid, runId, answer);
                            }

                            @Override
                            public void onFailed(UUID toolCallId, String toolName, int round,
                                                 String errorCode) {
                                if (!send(emitter, ChatSseEvent.toolFailed(
                                        runId, sessionId, sequence.incrementAndGet(), traceId,
                                        toolCallId, toolName, errorCode))) {
                                    cancelDisconnectedRun(runUuid, runId, answer);
                                }
                            }

                            @Override
                            public void onApprovalRequired(
                                    UUID toolCallId, String toolName, int round,
                                    UUID approvalId, Instant expiresAt, String summary) {
                                if (!send(emitter, ChatSseEvent.approvalRequired(
                                        runId, sessionId, sequence.incrementAndGet(), traceId,
                                        toolCallId, toolName, approvalId, expiresAt, summary))) {
                                    cancelDisconnectedRun(runUuid, runId, answer);
                                }
                            }

                            @Override
                            public void onRetrying(
                                    UUID toolCallId,
                                    String toolName,
                                    int round,
                                    int nextAttempt,
                                    long delayMs,
                                    String errorCode) {
                                if (!send(emitter, ChatSseEvent.toolRetrying(
                                        runId, sessionId, sequence.incrementAndGet(), traceId,
                                        toolCallId, toolName, nextAttempt, delayMs, errorCode))) {
                                    cancelDisconnectedRun(runUuid, runId, answer);
                                }
                            }
                        },
                        () -> sessionRegistry.isActive(runId));
            }
            catch (ModelCallException exception) {
                failRunSafely(runUuid, answer, exception.code().name());
                send(emitter, ChatSseEvent.error(
                        runId, sessionId, sequence.incrementAndGet(), traceId,
                        exception.code().name()));
                sessionRegistry.complete(runId);
                emitter.complete();
                return emitter;
            }
            catch (AgentLoopService.AgentLoopException exception) {
                if (!sessionRegistry.isActive(runId)) return emitter;
                failRunSafely(runUuid, answer, exception.errorCode());
                send(emitter, ChatSseEvent.error(
                        runId, sessionId, sequence.incrementAndGet(), traceId,
                        exception.errorCode()));
                sessionRegistry.complete(runId);
                emitter.complete();
                return emitter;
            }
        }
        else {
            try {
                toolEvidence = releaseToolService.execute(
                        actor.workspaceId(), runUuid, userMessage, previousUserQuestions(history),
                        new ReleaseToolService.ToolProgressListener() {
                            @Override
                            public void onStarted(UUID toolCallId, String toolName) {
                                if (!send(emitter, ChatSseEvent.toolStarted(
                                        runId, sessionId, sequence.incrementAndGet(), traceId,
                                        toolCallId, toolName))) {
                                    cancelDisconnectedRun(runUuid, runId, answer);
                                }
                            }

                            @Override
                            public void onCompleted(UUID toolCallId, String toolName,
                                                    int releaseCount) {
                                if (!send(emitter, ChatSseEvent.toolCompleted(
                                        runId, sessionId, sequence.incrementAndGet(), traceId,
                                        toolCallId, toolName, releaseCount))) {
                                    cancelDisconnectedRun(runUuid, runId, answer);
                                }
                            }
                        });
            }
            catch (GitHubToolException exception) {
                String errorCode = toolErrorCode(exception.code());
                failRunSafely(runUuid, answer, errorCode);
                send(emitter, ChatSseEvent.error(
                        runId, sessionId, sequence.incrementAndGet(), traceId, errorCode));
                sessionRegistry.complete(runId);
                emitter.complete();
                return emitter;
            }
            if (!sessionRegistry.isActive(runId)) return emitter;

            ragEvidence = knowledgeRagService == null ? Optional.empty()
                    : knowledgeRagService.retrieve(
                    runUuid, actor.workspaceId(), actor.userId(),
                    "SYSTEM_ADMIN".equals(account.systemRole()),
                    retrievalQuery(history, userMessage),
                    new KnowledgeRagService.ToolProgressListener() {
                        @Override
                        public void onStarted(UUID toolCallId, String toolName) {
                            if (!send(emitter, ChatSseEvent.toolStarted(
                                    runId, sessionId, sequence.incrementAndGet(), traceId,
                                    toolCallId, toolName))) {
                                cancelDisconnectedRun(runUuid, runId, answer);
                            }
                        }

                        @Override
                        public void onCompleted(UUID toolCallId, String toolName,
                                                int resultCount, String model) {
                            if (!send(emitter, ChatSseEvent.retrievalCompleted(
                                    runId, sessionId, sequence.incrementAndGet(), traceId,
                                    toolCallId, toolName, resultCount, model))) {
                                cancelDisconnectedRun(runUuid, runId, answer);
                            }
                        }
                    });
            eventEvidence = projectEventEvidenceService == null ? Optional.empty()
                    : projectEventEvidenceService.retrieve(
                    runUuid, actor.workspaceId(), userMessage);
        }
        if (!sessionRegistry.isActive(runId)) return emitter;

        String evidenceAppendix = loopResult == null
                ? toolEvidence.map(ReleaseToolService.ToolEvidence::systemPromptAppendix).orElse("")
                + ragEvidence.map(KnowledgeRagService.RagEvidence::systemPromptAppendix).orElse("")
                + eventEvidence.map(ProjectEventEvidenceService.EventEvidence::systemPromptAppendix)
                .orElse("")
                : loopResult.systemPromptAppendix();
        List<String> citations = loopResult == null
                ? java.util.stream.Stream.of(
                toolEvidence.stream().flatMap(item -> item.sourceUrls().stream()),
                ragEvidence.stream().flatMap(item -> item.sourceUrls().stream()),
                eventEvidence.stream().flatMap(item -> item.sourceUrls().stream()))
                .flatMap(java.util.function.Function.identity()).distinct().toList()
                : loopResult.sourceUrls();
        List<ChatCitation> citationDetails = new java.util.ArrayList<>();
        if (loopResult != null) {
            citationDetails.addAll(loopResult.citations());
        }
        else {
            toolEvidence.ifPresent(evidenceItem -> {
                for (int index = 0; index < evidenceItem.sourceUrls().size(); index++) {
                    String url = evidenceItem.sourceUrls().get(index);
                    citationDetails.add(new ChatCitation(
                            "R" + (index + 1), "GitHub Release", url, null, null,
                            "GITHUB_RELEASE", null));
                }
            });
            ragEvidence.ifPresent(evidenceItem -> {
                if (!evidenceItem.citations().isEmpty()) {
                    citationDetails.addAll(evidenceItem.citations());
                    return;
                }
                for (int index = 0; index < evidenceItem.sourceUrls().size(); index++) {
                    String url = evidenceItem.sourceUrls().get(index);
                    citationDetails.add(new ChatCitation(
                            "S" + (index + 1), "官方项目文档", url, null, null,
                            "OFFICIAL_DOCUMENT", null));
                }
            });
            eventEvidence.ifPresent(evidenceItem -> citationDetails.addAll(evidenceItem.citations()));
        }
        final ModelUsage planningUsage = loopResult == null
                ? ModelUsage.unknown() : loopResult.planningUsage();

        String systemPrompt = SYSTEM_PROMPT + guardrail.systemPolicy()
                + userMemoryStore.prompt(actor, 20) + evidenceAppendix;
        try {
            if (loopResult == null) {
                toolEvidence.ifPresent(evidenceItem ->
                        guardrail.verifyTrustedReleaseSources(evidenceItem.sourceUrls()));
                ragEvidence.ifPresent(evidenceItem ->
                        guardrail.verifyTrustedKnowledgeSources(evidenceItem.sourceUrls()));
                eventEvidence.ifPresent(evidenceItem ->
                        guardrail.verifyTrustedProjectEventSources(evidenceItem.sourceUrls()));
            }
            else {
                guardrail.verifyTrustedReleaseSources(citationDetails.stream()
                        .filter(item -> "GITHUB_RELEASE".equals(item.sourceType()))
                        .map(ChatCitation::url).toList());
                guardrail.verifyTrustedProjectEventSources(citationDetails.stream()
                        .filter(item -> item.sourceType() != null
                                && item.sourceType().startsWith("GITHUB_")
                                && !"GITHUB_RELEASE".equals(item.sourceType()))
                        .map(ChatCitation::url).toList());
                guardrail.verifyTrustedKnowledgeSources(citationDetails.stream()
                        .filter(item -> item.sourceType() == null
                                || !item.sourceType().startsWith("GITHUB_"))
                        .map(ChatCitation::url).toList());
            }
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
                            ModelUsage totalUsage = planningUsage.plus(event.usage());
                            ChatStreamEvent completedEvent = ChatStreamEvent.completed(
                                    event.provider(), event.model(), totalUsage,
                                    event.duration(), event.timeToFirstToken());
                            try {
                                chatRunStore.succeedRunWithCitations(
                                        runUuid,
                                        answer.toString(),
                                        completedEvent.provider(),
                                        completedEvent.model(),
                                        completedEvent.usage(),
                                        citationDetails,
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
                                    completedEvent,
                                    citations,
                                    citationDetails));
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

    private static AgentToolDefinition.AccessLevel toolAccess(
            String systemRole, String workspaceRole) {
        if ("SYSTEM_ADMIN".equals(systemRole)) {
            return AgentToolDefinition.AccessLevel.SYSTEM_ADMIN;
        }
        if ("OWNER".equals(workspaceRole)) {
            return AgentToolDefinition.AccessLevel.WORKSPACE_OWNER;
        }
        return AgentToolDefinition.AccessLevel.WORKSPACE_MEMBER;
    }

    private static String previousUserQuestions(List<ChatRunStore.StoredMessage> history) {
        return history.stream()
                .filter(message -> "USER".equals(message.role()))
                .map(ChatRunStore.StoredMessage::content)
                .reduce((ignored, latest) -> latest)
                .orElse("");
    }

    private static String retrievalQuery(List<ChatRunStore.StoredMessage> history,
                                         String currentQuestion) {
        String previous = previousUserQuestions(history);
        if (previous.isBlank() || !looksLikeFollowUp(currentQuestion)) {
            return currentQuestion;
        }
        String query = previous + "\n当前追问：" + currentQuestion;
        return query.length() <= 4_000 ? query : query.substring(query.length() - 4_000);
    }

    private static boolean looksLikeFollowUp(String question) {
        String normalized = question.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("这个") || normalized.contains("这些")
                || normalized.contains("它") || normalized.contains("上述")
                || normalized.contains("上一个") || normalized.contains("相比")
                || normalized.contains("继续") || normalized.contains("那它")
                || normalized.contains("this") || normalized.contains("that")
                || normalized.contains("it ");
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
        UUID runUuid;
        try {
            runUuid = UUID.fromString(runId);
        }
        catch (IllegalArgumentException exception) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Agent run not found");
        }
        if (!chatRunStore.ownsRun(CurrentAccount.actor(request), runUuid)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Agent run not found");
        }
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
            Integer retrievalCount,
            String retrievalModel,
            List<String> sources,
            List<ChatCitation> citations) {

        static ChatSseEvent started(String runId, UUID sessionId, long sequence, String traceId) {
            return new ChatSseEvent(
                    "started", runId, sessionId, sequence, Instant.now(), traceId,
                    null, null, null, null, null, null, null,
                    null, null, null, null, null, List.of(), List.of());
        }

        static ChatSseEvent delta(
                String runId, UUID sessionId, long sequence, String traceId, String content) {
            return new ChatSseEvent(
                    "delta", runId, sessionId, sequence, Instant.now(), traceId,
                    content, null, null, null, null, null, null,
                    null, null, null, null, null, List.of(), List.of());
        }

        static ChatSseEvent completed(
                String runId,
                UUID sessionId,
                long sequence,
                String traceId,
                ChatStreamEvent event,
                List<String> sources,
                List<ChatCitation> citations) {
            return new ChatSseEvent(
                    "completed", runId, sessionId, sequence, Instant.now(), traceId,
                    null, event.provider(), event.model(), event.usage(),
                    event.duration().toMillis(),
                    event.timeToFirstToken() == null ? null : event.timeToFirstToken().toMillis(),
                    null, null, null, null, null, null, sources, citations);
        }

        static ChatSseEvent cancelled(
                String runId, UUID sessionId, long sequence, String traceId) {
            return new ChatSseEvent(
                    "cancelled", runId, sessionId, sequence, Instant.now(), traceId,
                    null, null, null, null, null, null, null,
                    null, null, null, null, null, List.of(), List.of());
        }

        static ChatSseEvent error(
                String runId, UUID sessionId, long sequence, String traceId, String errorCode) {
            return new ChatSseEvent(
                    "error", runId, sessionId, sequence, Instant.now(), traceId,
                    null, null, null, null, null, null, errorCode,
                    null, null, null, null, null, List.of(), List.of());
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
                    toolName, toolCallId, null, null, null, List.of(), List.of());
        }
        static ChatSseEvent toolRetrying(
                String runId,
                UUID sessionId,
                long sequence,
                String traceId,
                UUID toolCallId,
                String toolName,
                int nextAttempt,
                long delayMs,
                String errorCode) {
            String content = "第 " + nextAttempt + " 次尝试将在 "
                    + delayMs + " ms 后开始";
            return new ChatSseEvent(
                    "tool_retrying", runId, sessionId, sequence, Instant.now(), traceId,
                    content, null, null, null, null, null, errorCode,
                    toolName, toolCallId, null, null, null, List.of(), List.of());
        }

        static ChatSseEvent approvalRequired(
                String runId,
                UUID sessionId,
                long sequence,
                String traceId,
                UUID toolCallId,
                String toolName,
                UUID approvalId,
                Instant expiresAt,
                String summary) {
            String content = summary + "\n审批编号：" + approvalId
                    + "\n请前往“操作审批”页面确认；到期时间：" + expiresAt;
            return new ChatSseEvent(
                    "tool_approval_required", runId, sessionId, sequence,
                    Instant.now(), traceId, content, null, null, null,
                    null, null, null, toolName, toolCallId,
                    null, null, null, List.of(), List.of());
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
                    toolName, toolCallId, releaseCount, null, null, List.of(), List.of());
        }

        static ChatSseEvent toolFailed(
                String runId,
                UUID sessionId,
                long sequence,
                String traceId,
                UUID toolCallId,
                String toolName,
                String errorCode) {
            return new ChatSseEvent(
                    "tool_failed", runId, sessionId, sequence, Instant.now(), traceId,
                    null, null, null, null, null, null, errorCode,
                    toolName, toolCallId, null, null, null, List.of(), List.of());
        }

        static ChatSseEvent retrievalCompleted(
                String runId,
                UUID sessionId,
                long sequence,
                String traceId,
                UUID toolCallId,
                String toolName,
                int resultCount,
                String model) {
            return new ChatSseEvent(
                    "tool_completed", runId, sessionId, sequence, Instant.now(), traceId,
                    null, null, null, null, null, null, null,
                    toolName, toolCallId, null, resultCount, model, List.of(), List.of());
        }
    }
}
