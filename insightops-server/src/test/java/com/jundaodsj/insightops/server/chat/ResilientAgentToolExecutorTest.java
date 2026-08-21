package com.jundaodsj.insightops.server.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.server.tool.AgentRunExecutionBudget;
import com.jundaodsj.insightops.server.tool.AgentToolCircuitBreakerRegistry;
import com.jundaodsj.insightops.server.tool.AgentToolOperationalMetrics;
import com.jundaodsj.insightops.server.tool.AgentToolResilienceProperties;
import com.jundaodsj.insightops.server.tool.RegisteredToolExecutionService;
import com.jundaodsj.insightops.tool.application.AgentToolExecutionStore;
import com.jundaodsj.insightops.tool.application.registry.AgentToolDefinition;
import com.jundaodsj.insightops.tool.application.registry.AgentToolRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResilientAgentToolExecutorTest {

    private ResilientAgentToolExecutor executor;

    @AfterEach
    void closeExecutor() {
        if (executor != null) executor.close();
    }

    @Test
    void retriesTransientFailureInsideOneLogicalToolCall() {
        Fixture fixture = fixture(Duration.ofSeconds(2));
        AtomicInteger calls = new AtomicInteger();
        RecordingListener listener = new RecordingListener();

        Map<String, Object> result = executor.execute(
                fixture.session(), fixture.budget(), () -> true, listener, 1, () -> {
                    if (calls.incrementAndGet() == 1) {
                        throw new AgentToolDispatcher.DispatchException(
                                "TOOL_TRANSIENT_REMOTE");
                    }
                    return Map.of("value", "ok");
                });
        fixture.session().succeed(result);

        assertThat(calls).hasValue(2);
        assertThat(listener.retryAttempts).containsExactly(2);
        assertThat(fixture.store().attempts)
                .extracting(AttemptResult::status)
                .containsExactly("FAILED", "SUCCEEDED");
        assertThat(fixture.store().toolStarts).isEqualTo(1);
        assertThat(fixture.store().toolStatus).isEqualTo("SUCCEEDED");
    }

    @Test
    void doesNotRetryPermanentFailure() {
        Fixture fixture = fixture(Duration.ofSeconds(2));
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute(
                fixture.session(), fixture.budget(), () -> true,
                new RecordingListener(), 1, () -> {
                    calls.incrementAndGet();
                    throw new AgentToolDispatcher.DispatchException(
                            "TOOL_INPUT_INVALID");
                }))
                .isInstanceOfSatisfying(
                        AgentToolDispatcher.DispatchException.class,
                        failure -> assertThat(failure.errorCode())
                                .isEqualTo("TOOL_INPUT_INVALID"));

        assertThat(calls).hasValue(1);
        assertThat(fixture.store().attempts)
                .extracting(AttemptResult::retryable)
                .containsExactly(false);
    }

    @Test
    void enforcesRegistryTimeoutAndInterruptsLateWork() {
        Fixture fixture = fixture(Duration.ofMillis(100));
        long started = System.nanoTime();

        assertThatThrownBy(() -> executor.execute(
                fixture.session(), fixture.budget(), () -> true,
                new RecordingListener(), 1, () -> {
                    Thread.sleep(5_000);
                    return Map.of("value", "late");
                }))
                .isInstanceOfSatisfying(
                        AgentToolDispatcher.DispatchException.class,
                        failure -> {
                            assertThat(failure.errorCode()).isEqualTo("TOOL_TIMEOUT");
                            assertThat(failure.terminalStatus()).isEqualTo("TIMED_OUT");
                        });

        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isLessThan(Duration.ofSeconds(1));
        assertThat(fixture.store().attempts)
                .extracting(AttemptResult::status)
                .containsExactly("TIMED_OUT");
    }

    @Test
    void rejectsExecutionWhenRunWasCancelled() {
        Fixture fixture = fixture(Duration.ofSeconds(2));

        assertThatThrownBy(() -> executor.execute(
                fixture.session(), fixture.budget(), () -> false,
                new RecordingListener(), 1, () -> Map.of("value", "never")))
                .isInstanceOfSatisfying(
                        AgentToolDispatcher.DispatchException.class,
                        failure -> {
                            assertThat(failure.errorCode()).isEqualTo("TOOL_CANCELLED");
                            assertThat(failure.terminalStatus()).isEqualTo("CANCELLED");
                        });
        assertThat(fixture.store().attempts).isEmpty();
    }

    private Fixture fixture(Duration timeout) {
        AgentToolResilienceProperties properties = new AgentToolResilienceProperties();
        properties.setMaxAttempts(3);
        properties.setInitialBackoffMs(10);
        properties.setMaxBackoffMs(20);
        properties.setPollIntervalMs(20);
        properties.setCircuitMinimumCalls(5);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AgentToolOperationalMetrics metrics = new AgentToolOperationalMetrics(meterRegistry);
        executor = new ResilientAgentToolExecutor(
                properties,
                new AgentToolCircuitBreakerRegistry(properties, metrics),
                metrics);
        AgentToolDefinition definition = new AgentToolDefinition(
                "test_search", 1, "Search test evidence", true,
                AgentToolDefinition.AccessLevel.WORKSPACE_MEMBER,
                AgentToolDefinition.RiskLevel.READ_ONLY,
                AgentToolDefinition.ApprovalPolicy.NOT_REQUIRED,
                timeout, 10_000,
                List.of(AgentToolDefinition.Parameter.string(
                        "query", "Search query", true, 100)),
                List.of(AgentToolDefinition.Parameter.string(
                        "value", "Search result", true, 100)));
        RecordingStore store = new RecordingStore();
        RegisteredToolExecutionService service = new RegisteredToolExecutionService(
                new AgentToolRegistry(List.of(definition)), store, new ObjectMapper());
        RegisteredToolExecutionService.Session session = service.start(
                UUID.randomUUID(), 1, 1, 1, definition.name(),
                Map.of("query", "test"));
        return new Fixture(
                session,
                new AgentRunExecutionBudget(
                        Duration.ofSeconds(5), 8, Duration.ofSeconds(4)),
                store);
    }

    private record Fixture(
            RegisteredToolExecutionService.Session session,
            AgentRunExecutionBudget budget,
            RecordingStore store) {
    }

    private record AttemptResult(String status, boolean retryable, String errorCode) {
    }

    private static final class RecordingListener
            implements AgentToolDispatcher.ProgressListener {
        private final List<Integer> retryAttempts = new ArrayList<>();

        @Override
        public void onStarted(UUID toolCallId, String toolName, int round) {
        }

        @Override
        public void onCompleted(
                UUID toolCallId, String toolName, int round, int resultCount, String model) {
        }

        @Override
        public void onFailed(UUID toolCallId, String toolName, int round, String errorCode) {
        }

        @Override
        public void onRetrying(
                UUID toolCallId,
                String toolName,
                int round,
                int nextAttempt,
                long delayMs,
                String errorCode) {
            retryAttempts.add(nextAttempt);
        }
    }

    private static final class RecordingStore implements AgentToolExecutionStore {
        private final List<AttemptResult> attempts = new ArrayList<>();
        private int toolStarts;
        private String toolStatus = "RUNNING";

        @Override
        public void startTool(
                UUID runId,
                UUID stepId,
                UUID toolCallId,
                int stepNo,
                String toolName,
                String idempotencyKey,
                String requestPayload,
                Instant startedAt) {
            toolStarts++;
        }

        @Override
        public void succeedTool(
                UUID runId,
                UUID stepId,
                UUID toolCallId,
                String resultPayload,
                long durationMs,
                Instant finishedAt) {
            toolStatus = "SUCCEEDED";
        }

        @Override
        public void failTool(
                UUID stepId,
                UUID toolCallId,
                String errorCode,
                long durationMs,
                Instant finishedAt) {
            toolStatus = "FAILED";
        }

        @Override
        public void finishTool(
                UUID stepId,
                UUID toolCallId,
                String status,
                String errorCode,
                long durationMs,
                Instant finishedAt) {
            toolStatus = status;
        }

        @Override
        public void startAttempt(
                UUID attemptId,
                UUID toolCallId,
                int attemptNo,
                Instant startedAt) {
        }

        @Override
        public void finishAttempt(
                UUID attemptId,
                String status,
                String errorCode,
                boolean retryable,
                long retryDelayMs,
                long durationMs,
                Instant finishedAt) {
            attempts.add(new AttemptResult(status, retryable, errorCode));
        }
    }
}
