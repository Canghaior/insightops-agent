package com.jundaodsj.insightops.server.chat;

import com.jundaodsj.insightops.server.tool.AgentRunExecutionBudget;
import com.jundaodsj.insightops.server.tool.AgentToolCircuitBreakerRegistry;
import com.jundaodsj.insightops.server.tool.AgentToolOperationalMetrics;
import com.jundaodsj.insightops.server.tool.AgentToolResilienceProperties;
import com.jundaodsj.insightops.server.tool.RegisteredToolExecutionService;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

@Component
public class ResilientAgentToolExecutor {

    private static final Set<String> RETRYABLE = Set.of(
            "TOOL_RATE_LIMITED",
            "TOOL_TRANSIENT_REMOTE",
            "TOOL_TIMEOUT",
            "EMBEDDING_UNAVAILABLE",
            "RETRIEVAL_ERROR",
            "EVENT_RETRIEVAL_ERROR");

    private final AgentToolResilienceProperties properties;
    private final AgentToolCircuitBreakerRegistry circuitBreakers;
    private final AgentToolOperationalMetrics metrics;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ResilientAgentToolExecutor(
            AgentToolResilienceProperties properties,
            AgentToolCircuitBreakerRegistry circuitBreakers,
            AgentToolOperationalMetrics metrics) {
        this.properties = properties;
        this.circuitBreakers = circuitBreakers;
        this.metrics = metrics;
    }

    public <T> T execute(
            RegisteredToolExecutionService.Session session,
            AgentRunExecutionBudget budget,
            BooleanSupplier active,
            AgentToolDispatcher.ProgressListener listener,
            int round,
            Callable<T> operation) {
        Duration allowed = minimum(
                session.definition().timeout(),
                budget.remaining(),
                budget.toolDurationRemaining());
        if (allowed.isZero()) {
            throw new AgentToolDispatcher.DispatchException(
                    "TOOL_RUN_BUDGET_EXHAUSTED", "FAILED");
        }
        Instant deadline = Instant.now().plus(allowed);
        int maxAttempts = properties.isEnabled()
                ? Math.max(1, Math.min(5, properties.getMaxAttempts())) : 1;

        for (int attemptNo = 1; attemptNo <= maxAttempts; attemptNo++) {
            ensureActiveForControl(active);
            AgentToolCircuitBreakerRegistry.Permit permit =
                    circuitBreakers.acquire(session.toolName());
            if (properties.isEnabled() && !permit.allowed()) {
                RegisteredToolExecutionService.Attempt attempt =
                        session.startAttempt(attemptNo);
                session.finishAttempt(
                        attempt, "CIRCUIT_OPEN", "TOOL_CIRCUIT_OPEN", false, 0);
                metrics.attempt(session.toolName(), "circuit_open");
                metrics.circuitRejected(permit.group());
                throw new AgentToolDispatcher.DispatchException(
                        "TOOL_CIRCUIT_OPEN", "FAILED");
            }
            if (!budget.tryAcquireAttempt()) {
                permit.ignored();
                throw new AgentToolDispatcher.DispatchException(
                        "TOOL_RUN_BUDGET_EXHAUSTED", "FAILED");
            }

            RegisteredToolExecutionService.Attempt attempt =
                    session.startAttempt(attemptNo);
            Instant attemptStartedAt = Instant.now();
            Future<T> future = executor.submit(operation);
            try {
                T result = await(future, deadline, active);
                Duration duration = Duration.between(attemptStartedAt, Instant.now());
                budget.recordToolDuration(duration);
                session.finishAttempt(attempt, "SUCCEEDED", null, false, 0);
                permit.success();
                metrics.attempt(session.toolName(), "succeeded");
                return result;
            }
            catch (CancellationException exception) {
                future.cancel(true);
                Duration duration = Duration.between(attemptStartedAt, Instant.now());
                budget.recordToolDuration(duration);
                session.finishAttempt(
                        attempt, "CANCELLED", "TOOL_CANCELLED", false, 0);
                permit.ignored();
                metrics.attempt(session.toolName(), "cancelled");
                throw new AgentToolDispatcher.DispatchException(
                        "TOOL_CANCELLED", "CANCELLED", exception);
            }
            catch (TimeoutException exception) {
                future.cancel(true);
                Duration duration = Duration.between(attemptStartedAt, Instant.now());
                budget.recordToolDuration(duration);
                session.finishAttempt(
                        attempt, "TIMED_OUT", "TOOL_TIMEOUT", true, 0);
                permit.failure();
                metrics.attempt(session.toolName(), "timed_out");
                metrics.timeout(session.toolName());
                throw new AgentToolDispatcher.DispatchException(
                        "TOOL_TIMEOUT", "TIMED_OUT", exception);
            }
            catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                AgentToolDispatcher.DispatchException failure =
                        cause instanceof AgentToolDispatcher.DispatchException dispatch
                                ? dispatch
                                : new AgentToolDispatcher.DispatchException(
                                        "TOOL_INTERNAL_ERROR", cause);
                String errorCode = failure.errorCode();
                boolean retryable = properties.isEnabled() && RETRYABLE.contains(errorCode);
                Duration duration = Duration.between(attemptStartedAt, Instant.now());
                budget.recordToolDuration(duration);
                long backoffMs = backoffMs(attemptNo);
                boolean retry = retryable
                        && attemptNo < maxAttempts
                        && !budget.exhausted()
                        && Instant.now().plusMillis(backoffMs).isBefore(deadline);
                session.finishAttempt(
                        attempt,
                        errorCode.endsWith("TIMEOUT") ? "TIMED_OUT" : "FAILED",
                        errorCode,
                        retryable,
                        retry ? backoffMs : 0);
                if (retryable) permit.failure();
                else permit.ignored();
                metrics.attempt(session.toolName(), retry ? "retryable_failed" : "failed");

                if (!retry) {
                    String terminalCode = retryable && attemptNo > 1
                            ? "TOOL_RETRY_EXHAUSTED" : errorCode;
                    throw new AgentToolDispatcher.DispatchException(
                            terminalCode,
                            errorCode.endsWith("TIMEOUT") ? "TIMED_OUT" : "FAILED",
                            failure);
                }
                metrics.retry(session.toolName(), errorCode);
                listener.onRetrying(
                        session.toolCallId(), session.toolName(), round,
                        attemptNo + 1, backoffMs, errorCode);
                backoff(backoffMs, deadline, active);
            }
        }
        throw new AgentToolDispatcher.DispatchException(
                "TOOL_RETRY_EXHAUSTED", "FAILED");
    }

    private <T> T await(
            Future<T> future,
            Instant deadline,
            BooleanSupplier active)
            throws ExecutionException, TimeoutException {
        long pollMs = Math.max(20, Math.min(1_000, properties.getPollIntervalMs()));
        while (true) {
            ensureActive(active);
            long remainingMs = Duration.between(Instant.now(), deadline).toMillis();
            if (remainingMs <= 0) throw new TimeoutException("tool deadline exceeded");
            try {
                return future.get(Math.max(1, Math.min(pollMs, remainingMs)),
                        TimeUnit.MILLISECONDS);
            }
            catch (java.lang.InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new CancellationException("tool wait interrupted");
            }
            catch (TimeoutException exception) {
                if (!Instant.now().isBefore(deadline)) throw exception;
            }
        }
    }

    private void backoff(long delayMs, Instant deadline, BooleanSupplier active) {
        long pollMs = Math.max(20, Math.min(1_000, properties.getPollIntervalMs()));
        Instant until = Instant.now().plusMillis(delayMs);
        while (Instant.now().isBefore(until)) {
            ensureActiveForControl(active);
            if (!Instant.now().isBefore(deadline)) {
                throw new AgentToolDispatcher.DispatchException(
                        "TOOL_RUN_BUDGET_EXHAUSTED", "FAILED");
            }
            long remaining = Duration.between(Instant.now(), until).toMillis();
            try {
                Thread.sleep(Math.max(1, Math.min(pollMs, remaining)));
            }
            catch (java.lang.InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AgentToolDispatcher.DispatchException(
                        "TOOL_CANCELLED", "CANCELLED", exception);
            }
        }
    }

    private long backoffMs(int failedAttemptNo) {
        long initial = Math.max(10, properties.getInitialBackoffMs());
        long maximum = Math.max(initial, properties.getMaxBackoffMs());
        long multiplier = 1L << Math.min(10, Math.max(0, failedAttemptNo - 1));
        return Math.min(maximum, initial * multiplier);
    }


    private static void ensureActiveForControl(BooleanSupplier active) {
        try {
            ensureActive(active);
        }
        catch (CancellationException exception) {
            throw new AgentToolDispatcher.DispatchException(
                    "TOOL_CANCELLED", "CANCELLED", exception);
        }
    }
    private static void ensureActive(BooleanSupplier active) {
        if (active != null && !active.getAsBoolean()) {
            throw new CancellationException("agent run is no longer active");
        }
    }

    private static Duration minimum(Duration... values) {
        Duration result = values[0];
        for (Duration value : values) {
            if (value.compareTo(result) < 0) result = value;
        }
        return result.isNegative() ? Duration.ZERO : result;
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }
}
