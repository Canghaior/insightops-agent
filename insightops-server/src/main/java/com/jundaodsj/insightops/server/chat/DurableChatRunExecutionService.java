package com.jundaodsj.insightops.server.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.agent.application.AgentOrchestrationStore;
import com.jundaodsj.insightops.conversation.application.ChatCitation;
import com.jundaodsj.insightops.conversation.application.DurableChatRunStore;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.infrastructure.config.DeepSeekModelProperties;
import com.jundaodsj.insightops.memory.application.UserMemoryStore;
import com.jundaodsj.insightops.model.application.ChatModelRequest;
import com.jundaodsj.insightops.model.application.ChatStreamEvent;
import com.jundaodsj.insightops.model.application.ChatStreamEventType;
import com.jundaodsj.insightops.model.application.ChatStreamListener;
import com.jundaodsj.insightops.model.application.ChatStreamSession;
import com.jundaodsj.insightops.model.application.ModelCallException;
import com.jundaodsj.insightops.model.application.ModelUsage;
import com.jundaodsj.insightops.model.application.StreamingChatModelGateway;
import com.jundaodsj.insightops.server.api.ChatStreamController.ChatSseEvent;
import com.jundaodsj.insightops.server.api.ChatStreamController.OrchestrationSsePayload;
import com.jundaodsj.insightops.tool.application.registry.AgentToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class DurableChatRunExecutionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DurableChatRunExecutionService.class);
    private static final String SYSTEM_PROMPT = """
            你是 InsightOps Agent，面向 Java 开发者、架构师和技术负责人回答 AI 开源项目问题。
            当前可使用 GitHub Release、Issue、Pull Request、Security Advisory、官方文档、官方博客/RSS、Roadmap 与当前用户有权访问的上传资料。
            不得声称查询了系统证据中没有出现的来源。
            如果系统提示中附有工具或知识库证据，只能基于该证据回答可验证事实，并为关键事实保留 [S#] 引用或官方 URL。
            如果没有证据，不要编造实时版本、发布日期、接口能力或来源链接；其他问题使用中文清晰、简洁地回答。
            """;

    private final DurableChatRunStore store;
    private final AgentLoopService agentLoop;
    private final StreamingChatModelGateway streamingGateway;
    private final DeepSeekModelProperties modelProperties;
    private final P0ChatGuardrail guardrail;
    private final UserMemoryStore userMemoryStore;
    private final DurableChatRunTerminalService terminalService;
    private final DurableChatRunProperties properties;
    private final ScheduledExecutorService heartbeatExecutor;
    private final DurableChatRunMetrics metrics;
    private final ObjectMapper json;

    public DurableChatRunExecutionService(
            DurableChatRunStore store,
            AgentLoopService agentLoop,
            StreamingChatModelGateway streamingGateway,
            DeepSeekModelProperties modelProperties,
            P0ChatGuardrail guardrail,
            UserMemoryStore userMemoryStore,
            DurableChatRunTerminalService terminalService,
            DurableChatRunProperties properties,
            @Qualifier("durableChatHeartbeatExecutor") ScheduledExecutorService heartbeatExecutor,
            DurableChatRunMetrics metrics,
            ObjectMapper json) {
        this.store = store;
        this.agentLoop = agentLoop;
        this.streamingGateway = streamingGateway;
        this.modelProperties = modelProperties;
        this.guardrail = guardrail;
        this.userMemoryStore = userMemoryStore;
        this.terminalService = terminalService;
        this.properties = properties;
        this.heartbeatExecutor = heartbeatExecutor;
        this.metrics = metrics;
        this.json = json;
    }

    public void execute(DurableChatRunStore.WorkLease lease) {
        StringBuffer answer = new StringBuffer();
        try (LeaseGuard guard = new LeaseGuard(lease)) {
            guard.renewNow();
            if (lease.attemptCount() > lease.maxAttempts()) {
                fail(lease, answer, "AGENT_RUN_ATTEMPTS_EXHAUSTED", guard);
                return;
            }
            DurableChatRunStore.AttemptPreparation preparation = store.prepareAttempt(
                    lease.runId(), lease.leaseToken(), Instant.now());
            if (preparation.recovered()) {
                metrics.recovered();
                append(lease, runRecovered(lease, preparation.recoveryCheckpointId()));
            }
            UUID recoveryCheckpointId = preparation.recoveryCheckpointId();
            AgentLoopService.LoopResult loopResult = agentLoop.run(
                    new AgentLoopService.LoopRequest(
                            lease.runId(), lease.workspaceId(), lease.ownerUserId(),
                            lease.systemAdmin(), AgentToolDefinition.AccessLevel.valueOf(lease.accessLevel()),
                            lease.contextualPrompt(), lease.resumeCheckpointId(), null, false,
                            recoveryCheckpointId),
                    progress(lease), guard::isActive);
            guard.renewNow();
            verifySources(loopResult.citations());

            ActorContext actor = new ActorContext(lease.ownerUserId(), lease.workspaceId());
            String systemPrompt = SYSTEM_PROMPT + guardrail.systemPolicy()
                    + userMemoryStore.prompt(actor, 20) + loopResult.systemPromptAppendix();
            Completion completion = streamAnswer(
                    lease, guard, answer, systemPrompt, lease.contextualPrompt(),
                    loopResult.planningUsage());
            ChatSseEvent completed = completed(lease, completion.event(),
                    loopResult.sourceUrls(), loopResult.citations());
            if (!terminalService.succeed(
                    lease, answer.toString(), completion.event().provider(),
                    completion.event().model(), completion.event().usage(),
                    loopResult.citations(), json(completed), Instant.now())) {
                throw new AgentRunLeaseLostException();
            }
            settleCostSafely(lease.runId(), completion.event().usage());
        }
        catch (AgentLoopService.AgentLoopPausedException exception) {
            ChatSseEvent event = planPaused(lease, exception.checkpointId());
            if (!terminalService.pause(
                    lease, answer.toString(), json(event), Instant.now())) metrics.leaseLost();
        }
        catch (AgentRunCancelledException exception) {
            cancel(lease, answer);
        }
        catch (AgentRunLeaseLostException exception) {
            metrics.leaseLost();
        }
        catch (ModelCallException exception) {
            fail(lease, answer, exception.code().name(), null);
        }
        catch (AgentLoopService.AgentLoopException exception) {
            if ("CANCELLED".equals(exception.errorCode())) cancel(lease, answer);
            else fail(lease, answer, exception.errorCode(), null);
        }
        catch (RuntimeException exception) {
            LOGGER.error("Durable chat run {} failed", lease.runId(), exception);
            fail(lease, answer, failureCode(exception), null);
        }
    }

    private Completion streamAnswer(
            DurableChatRunStore.WorkLease lease,
            LeaseGuard guard,
            StringBuffer answer,
            String systemPrompt,
            String userPrompt,
            ModelUsage planningUsage) {
        CompletableFuture<ChatStreamEvent> terminal = new CompletableFuture<>();
        ChatStreamSession session = streamingGateway.stream(
                new ChatModelRequest(systemPrompt, userPrompt,
                        modelProperties.temperature(), modelProperties.maxOutputTokens()),
                new ChatStreamListener() {
                    @Override
                    public void onEvent(ChatStreamEvent event) {
                        try {
                            if (event.type() == ChatStreamEventType.CONTENT_DELTA) {
                                answer.append(event.content());
                                append(lease, delta(lease, event.content()));
                                return;
                            }
                            ModelUsage total = planningUsage.plus(event.usage());
                            terminal.complete(ChatStreamEvent.completed(
                                    event.provider(), event.model(), total,
                                    event.duration(), event.timeToFirstToken()));
                        }
                        catch (RuntimeException exception) {
                            terminal.completeExceptionally(exception);
                        }
                    }

                    @Override
                    public void onError(ModelCallException exception) {
                        terminal.completeExceptionally(exception);
                    }
                });
        while (true) {
            try {
                return new Completion(terminal.get(250, TimeUnit.MILLISECONDS));
            }
            catch (TimeoutException ignored) {
                if (!guard.isActive()) {
                    session.cancel();
                    guard.throwControlFailure();
                }
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                session.cancel();
                throw new AgentRunLeaseLostException();
            }
            catch (ExecutionException exception) {
                session.cancel();
                Throwable cause = exception.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException(cause);
            }
        }
    }

    private AgentToolDispatcher.ProgressListener progress(DurableChatRunStore.WorkLease lease) {
        return new AgentToolDispatcher.ProgressListener() {
            @Override
            public void onStarted(UUID toolCallId, String toolName, int round) {
                append(lease, toolEvent("tool_started", lease, toolCallId, toolName, null, null));
            }

            @Override
            public void onCompleted(
                    UUID toolCallId, String toolName, int round, int resultCount, String model) {
                append(lease, toolCompleted(lease, toolCallId, toolName, resultCount, model));
            }

            @Override
            public void onFailed(
                    UUID toolCallId, String toolName, int round, String errorCode) {
                append(lease, toolEvent("tool_failed", lease, toolCallId, toolName, errorCode, null));
            }

            @Override
            public void onApprovalRequired(
                    UUID toolCallId, String toolName, int round,
                    UUID approvalId, Instant expiresAt, String summary) {
                append(lease, toolEvent(
                        "tool_approval_required", lease, toolCallId, toolName, null,
                        summary + "\n审批编号：" + approvalId + "\n到期时间：" + expiresAt));
            }

            @Override
            public void onRetrying(
                    UUID toolCallId, String toolName, int round, int nextAttempt,
                    long delayMs, String errorCode) {
                append(lease, toolEvent(
                        "tool_retrying", lease, toolCallId, toolName, errorCode,
                        "第 " + nextAttempt + " 次尝试将在 " + delayMs + " ms 后开始"));
            }

            @Override
            public void onPlanCreated(UUID planId, int version, int maxNodes, int maxParallelism) {
                append(lease, event(
                        "plan_created", lease, null, null, null, null, null, null,
                        new OrchestrationSsePayload(
                                planId.toString(), null, "ACTIVE", version, null,
                                maxNodes, maxParallelism, null, null, null, null,
                                List.of(), null, null)));
            }

            @Override
            public void onPlanNodeState(
                    UUID nodeId, String toolName, int round, String status,
                    List<UUID> dependencyIds, String errorCode) {
                append(lease, event(
                        "plan_node_state", lease, null, errorCode, toolName, null,
                        null, null, new OrchestrationSsePayload(
                                null, nodeId.toString(), status, null, round,
                                null, null, null, null, null, null,
                                dependencyIds.stream().map(UUID::toString).toList(),
                                errorCode, null)));
            }

            @Override
            public void onBudgetUpdated(AgentOrchestrationStore.BudgetSnapshot budget) {
                append(lease, budgetEvent("budget_updated", lease, budget));
            }

            @Override
            public void onBudgetExhausted(AgentOrchestrationStore.BudgetSnapshot budget) {
                append(lease, budgetEvent("budget_exhausted", lease, budget));
            }
        };
    }

    private void verifySources(List<ChatCitation> citations) {
        guardrail.verifyTrustedReleaseSources(citations.stream()
                .filter(item -> "GITHUB_RELEASE".equals(item.sourceType()))
                .map(ChatCitation::url).toList());
        guardrail.verifyTrustedProjectEventSources(citations.stream()
                .filter(item -> item.sourceType() != null && item.sourceType().startsWith("GITHUB_")
                        && !"GITHUB_RELEASE".equals(item.sourceType()))
                .map(ChatCitation::url).toList());
        guardrail.verifyTrustedKnowledgeSources(citations.stream()
                .filter(item -> item.sourceType() == null || !item.sourceType().startsWith("GITHUB_"))
                .map(ChatCitation::url).toList());
    }

    private void append(DurableChatRunStore.WorkLease lease, ChatSseEvent event) {
        if (store.appendEvent(
                lease.runId(), lease.leaseToken(), event.type(), json(event), Instant.now()).isEmpty()) {
            DurableChatRunStore.LeaseControl control = store.renewLease(
                    lease.runId(), lease.leaseToken(), properties.leaseDuration(), Instant.now());
            if (control == DurableChatRunStore.LeaseControl.CANCEL_REQUESTED) {
                throw new AgentRunCancelledException();
            }
            throw new AgentRunLeaseLostException();
        }
    }

    private void cancel(DurableChatRunStore.WorkLease lease, StringBuffer answer) {
        if (terminalService.cancel(
                lease, answer.toString(), json(simple("cancelled", lease, null, null)),
                Instant.now())) {
            releaseCostSafely(lease.runId(), "CANCELLED");
            metrics.cancelled();
        }
        else metrics.leaseLost();
    }

    private void fail(
            DurableChatRunStore.WorkLease lease, StringBuffer answer,
            String failureCode, LeaseGuard guard) {
        DurableChatRunStore.LeaseControl control = guard == null
                ? store.renewLease(lease.runId(), lease.leaseToken(),
                        properties.leaseDuration(), Instant.now())
                : guard.control();
        if (control == DurableChatRunStore.LeaseControl.CANCEL_REQUESTED) {
            cancel(lease, answer);
            return;
        }
        if (control == DurableChatRunStore.LeaseControl.LOST) {
            metrics.leaseLost();
            return;
        }
        if (terminalService.fail(
                lease, answer.toString(), failureCode,
                json(simple("error", lease, null, failureCode)), Instant.now())) {
            releaseCostSafely(lease.runId(), failureCode);
        }
        else metrics.leaseLost();
    }

    private void releaseCostSafely(UUID runId, String reason) {
        try { agentLoop.releaseCost(runId, reason); }
        catch (RuntimeException exception) {
            LOGGER.error("Failed to release durable Agent cost for run {}", runId, exception);
        }
    }

    private void settleCostSafely(UUID runId, ModelUsage usage) {
        try { agentLoop.settleCost(runId, usage); }
        catch (RuntimeException exception) {
            LOGGER.error("Failed to settle durable Agent cost for run {}", runId, exception);
        }
    }

    private ChatSseEvent runRecovered(
            DurableChatRunStore.WorkLease lease, UUID checkpointId) {
        return event("run_recovered", lease,
                checkpointId == null ? "从头安全重放" : "从安全点 " + checkpointId + " 恢复",
                null, null, null, null, null,
                new OrchestrationSsePayload(
                        null, null, "RECOVERING", null, null, null, null,
                        null, null, null, null, List.of(), null, null));
    }

    private ChatSseEvent delta(DurableChatRunStore.WorkLease lease, String content) {
        return event("delta", lease, content, null, null, null, null, null, null);
    }

    private ChatSseEvent completed(
            DurableChatRunStore.WorkLease lease, ChatStreamEvent value,
            List<String> sources, List<ChatCitation> citations) {
        return event("completed", lease, null, null, null, null, sources, citations, null,
                value.provider(), value.model(), value.usage(), value.duration().toMillis(),
                value.timeToFirstToken() == null ? null : value.timeToFirstToken().toMillis(),
                null, null, null);
    }

    private ChatSseEvent planPaused(DurableChatRunStore.WorkLease lease, UUID checkpointId) {
        return event("plan_paused", lease, checkpointId.toString(), null, null, null,
                null, null, new OrchestrationSsePayload(
                        null, null, "PAUSED", null, null, null, null,
                        null, null, null, null, List.of(), null, null));
    }

    private ChatSseEvent toolCompleted(
            DurableChatRunStore.WorkLease lease, UUID callId, String name,
            int resultCount, String model) {
        boolean retrieval = KnowledgeRagService.TOOL_NAME.equals(name);
        return event("tool_completed", lease, null, null, name, callId,
                null, null, null, null, null, null, null, null,
                retrieval ? null : resultCount, retrieval ? resultCount : null,
                retrieval ? model : null);
    }

    private ChatSseEvent toolEvent(
            String type, DurableChatRunStore.WorkLease lease, UUID callId,
            String name, String errorCode, String content) {
        return event(type, lease, content, errorCode, name, callId, null, null, null);
    }

    private ChatSseEvent budgetEvent(
            String type, DurableChatRunStore.WorkLease lease,
            AgentOrchestrationStore.BudgetSnapshot budget) {
        return event(type, lease, null, null, null, null, null, null,
                new OrchestrationSsePayload(
                        null, null, budget.status(), null, null, null, null,
                        budget.usedNodes(), budget.usedToolAttempts(), budget.usedModelTokens(),
                        budget.estimatedCostCny(), List.of(), null, budget.exhaustionReason()));
    }

    private ChatSseEvent simple(
            String type, DurableChatRunStore.WorkLease lease,
            String content, String errorCode) {
        return event(type, lease, content, errorCode, null, null, null, null, null);
    }

    private ChatSseEvent event(
            String type, DurableChatRunStore.WorkLease lease, String content, String errorCode,
            String toolName, UUID toolCallId, List<String> sources,
            List<ChatCitation> citations, OrchestrationSsePayload orchestration) {
        return event(type, lease, content, errorCode, toolName, toolCallId,
                sources, citations, orchestration, null, null, null,
                null, null, null, null, null);
    }

    private ChatSseEvent event(
            String type, DurableChatRunStore.WorkLease lease, String content, String errorCode,
            String toolName, UUID toolCallId, List<String> sources,
            List<ChatCitation> citations, OrchestrationSsePayload orchestration,
            String provider, String model, ModelUsage usage, Long durationMs,
            Long firstTokenMs, Integer releaseCount, Integer retrievalCount,
            String retrievalModel) {
        return new ChatSseEvent(
                type, lease.runId().toString(), lease.sessionId(), 0, Instant.now(), lease.traceId(),
                content, provider, model, usage, durationMs, firstTokenMs, errorCode,
                toolName, toolCallId, releaseCount, retrievalCount, retrievalModel,
                sources == null ? List.of() : List.copyOf(sources),
                citations == null ? List.of() : List.copyOf(citations), orchestration);
    }

    private String json(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("CHAT_EVENT_SERIALIZATION_FAILED", exception);
        }
    }

    private static String failureCode(RuntimeException exception) {
        String code = exception.getClass().getSimpleName()
                .replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(java.util.Locale.ROOT);
        return code.length() <= 64 ? code : code.substring(0, 64);
    }

    private final class LeaseGuard implements AutoCloseable {
        private final DurableChatRunStore.WorkLease lease;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile DurableChatRunStore.LeaseControl control =
                DurableChatRunStore.LeaseControl.ACTIVE;
        private volatile Instant validUntil;
        private final ScheduledFuture<?> heartbeat;

        private LeaseGuard(DurableChatRunStore.WorkLease lease) {
            this.lease = lease;
            validUntil = lease.leaseExpiresAt();
            long intervalMs = Math.max(1_000, properties.heartbeatInterval().toMillis());
            heartbeat = heartbeatExecutor.scheduleWithFixedDelay(
                    this::heartbeat, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        }

        private void heartbeat() {
            if (closed.get() || control != DurableChatRunStore.LeaseControl.ACTIVE) return;
            Instant now = Instant.now();
            try {
                control = store.renewLease(
                        lease.runId(), lease.leaseToken(), properties.leaseDuration(), now);
                if (control == DurableChatRunStore.LeaseControl.ACTIVE) {
                    validUntil = now.plus(properties.leaseDuration());
                }
            }
            catch (RuntimeException exception) {
                if (!now.isBefore(validUntil)) control = DurableChatRunStore.LeaseControl.LOST;
            }
        }

        private void renewNow() {
            control = store.renewLease(
                    lease.runId(), lease.leaseToken(), properties.leaseDuration(), Instant.now());
            if (control == DurableChatRunStore.LeaseControl.ACTIVE) {
                validUntil = Instant.now().plus(properties.leaseDuration());
                return;
            }
            throwControlFailure();
        }

        private boolean isActive() {
            if (control != DurableChatRunStore.LeaseControl.ACTIVE) return false;
            if (!Instant.now().isBefore(validUntil)) {
                control = DurableChatRunStore.LeaseControl.LOST;
                return false;
            }
            return true;
        }

        private DurableChatRunStore.LeaseControl control() { return control; }

        private void throwControlFailure() {
            if (control == DurableChatRunStore.LeaseControl.CANCEL_REQUESTED) {
                throw new AgentRunCancelledException();
            }
            throw new AgentRunLeaseLostException();
        }

        @Override
        public void close() {
            closed.set(true);
            heartbeat.cancel(false);
        }
    }

    private record Completion(ChatStreamEvent event) {
    }

    private static final class AgentRunLeaseLostException extends RuntimeException {
    }

    private static final class AgentRunCancelledException extends RuntimeException {
    }
}
