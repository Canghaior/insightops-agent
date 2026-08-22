package com.jundaodsj.insightops.server.tool;

import com.jundaodsj.insightops.agent.application.AgentOrchestrationStore;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class AgentRunExecutionBudget {

    private final Instant deadline;
    private final int maxTotalAttempts;
    private final AtomicInteger remainingAttempts;
    private final long maxToolDurationNanos;
    private final AtomicLong usedToolDurationNanos = new AtomicLong();
    private final int maxNodes;
    private final AtomicInteger remainingNodes;
    private final long maxModelTokens;
    private final AtomicLong usedModelTokens = new AtomicLong();
    private final long maxCostMicros;
    private final AtomicLong usedCostMicros = new AtomicLong();
    private volatile String exhaustionReason;

    public AgentRunExecutionBudget(
            Duration runTimeout,
            int maxTotalAttempts,
            Duration maxToolDuration) {
        this(runTimeout, maxTotalAttempts, maxToolDuration,
                Integer.MAX_VALUE, Long.MAX_VALUE, new BigDecimal("999999999999.000000"));
    }

    public AgentRunExecutionBudget(
            Duration runTimeout,
            int maxTotalAttempts,
            Duration maxToolDuration,
            int maxNodes,
            long maxModelTokens,
            BigDecimal maxEstimatedCostCny) {
        if (runTimeout == null || runTimeout.isNegative() || runTimeout.isZero()) {
            throw new IllegalArgumentException("runTimeout must be positive");
        }
        if (maxTotalAttempts < 1) {
            throw new IllegalArgumentException("maxTotalAttempts must be positive");
        }
        if (maxToolDuration == null || maxToolDuration.isNegative()
                || maxToolDuration.isZero()) {
            throw new IllegalArgumentException("maxToolDuration must be positive");
        }
        if (maxNodes < 1) throw new IllegalArgumentException("maxNodes must be positive");
        if (maxModelTokens < 1) {
            throw new IllegalArgumentException("maxModelTokens must be positive");
        }
        if (maxEstimatedCostCny == null || maxEstimatedCostCny.signum() <= 0) {
            throw new IllegalArgumentException("maxEstimatedCostCny must be positive");
        }
        this.deadline = Instant.now().plus(runTimeout);
        this.maxTotalAttempts = maxTotalAttempts;
        this.remainingAttempts = new AtomicInteger(maxTotalAttempts);
        this.maxToolDurationNanos = maxToolDuration.toNanos();
        this.maxNodes = maxNodes;
        this.remainingNodes = new AtomicInteger(maxNodes);
        this.maxModelTokens = maxModelTokens;
        this.maxCostMicros = toMicros(maxEstimatedCostCny);
    }

    public AgentRunExecutionBudget(
            Duration runTimeout,
            int maxTotalAttempts,
            Duration maxToolDuration,
            int maxNodes,
            long maxModelTokens,
            BigDecimal maxEstimatedCostCny,
            AgentOrchestrationStore.BudgetSnapshot restored) {
        this(runTimeout, maxTotalAttempts, maxToolDuration, maxNodes,
                maxModelTokens, maxEstimatedCostCny);
        if (restored == null) return;
        remainingAttempts.set(Math.max(0, maxTotalAttempts - restored.usedToolAttempts()));
        remainingNodes.set(Math.max(0, maxNodes - restored.usedNodes()));
        usedModelTokens.set(Math.max(0, restored.usedModelTokens()));
        if (restored.estimatedCostCny() != null && restored.estimatedCostCny().signum() > 0) {
            usedCostMicros.set(toMicros(restored.estimatedCostCny()));
        }
        exhaustionReason = restored.exhaustionReason();
    }

    public Duration remaining() {
        Duration value = Duration.between(Instant.now(), deadline);
        return value.isNegative() ? Duration.ZERO : value;
    }

    public boolean tryAcquireAttempt() {
        while (true) {
            int current = remainingAttempts.get();
            if (current <= 0) {
                markExhausted("MAX_TOOL_ATTEMPTS");
                return false;
            }
            if (remaining().isZero()) {
                markExhausted("RUN_TIMEOUT");
                return false;
            }
            if (toolDurationExhausted()) {
                markExhausted("MAX_TOOL_DURATION");
                return false;
            }
            if (remainingAttempts.compareAndSet(current, current - 1)) return true;
        }
    }

    public int reserveNodes(int requested) {
        if (requested < 0) throw new IllegalArgumentException("requested must not be negative");
        if (requested == 0) return 0;
        while (true) {
            int current = remainingNodes.get();
            if (current <= 0) {
                markExhausted("MAX_NODES");
                return 0;
            }
            int reserved = Math.min(current, requested);
            if (remainingNodes.compareAndSet(current, current - reserved)) {
                if (reserved < requested || current - reserved == 0) {
                    markExhausted("MAX_NODES");
                }
                return reserved;
            }
        }
    }

    public boolean recordModelUsage(long tokens, BigDecimal estimatedCostCny) {
        if (tokens > 0) usedModelTokens.addAndGet(tokens);
        if (estimatedCostCny != null && estimatedCostCny.signum() > 0) {
            usedCostMicros.addAndGet(toMicros(estimatedCostCny));
        }
        if (usedModelTokens.get() >= maxModelTokens) markExhausted("MAX_MODEL_TOKENS");
        if (usedCostMicros.get() >= maxCostMicros) markExhausted("MAX_ESTIMATED_COST");
        return !planningExhausted();
    }

    public boolean canPlan() {
        return !planningExhausted() && !remaining().isZero();
    }

    public Duration toolDurationRemaining() {
        long remaining = maxToolDurationNanos - usedToolDurationNanos.get();
        return remaining <= 0 ? Duration.ZERO : Duration.ofNanos(remaining);
    }

    public void recordToolDuration(Duration duration) {
        if (duration != null && !duration.isNegative()) {
            usedToolDurationNanos.addAndGet(duration.toNanos());
        }
    }

    public boolean exhausted() {
        return remaining().isZero() || remainingAttempts.get() <= 0 || toolDurationExhausted();
    }

    public int attemptsRemaining() {
        return Math.max(0, remainingAttempts.get());
    }

    public int nodesRemaining() {
        return Math.max(0, remainingNodes.get());
    }

    public String exhaustionReason() {
        return exhaustionReason;
    }

    public void exhaust(String reason) {
        markExhausted(reason);
    }

    public AgentOrchestrationStore.BudgetSnapshot snapshot() {
        String reason = exhaustionReason;
        return new AgentOrchestrationStore.BudgetSnapshot(
                maxNodes - Math.max(0, remainingNodes.get()),
                maxTotalAttempts - Math.max(0, remainingAttempts.get()),
                Math.max(0, usedModelTokens.get()),
                BigDecimal.valueOf(Math.max(0, usedCostMicros.get()), 6),
                reason == null ? "ACTIVE" : "EXHAUSTED",
                reason);
    }

    private boolean planningExhausted() {
        return remainingNodes.get() <= 0
                || usedModelTokens.get() >= maxModelTokens
                || usedCostMicros.get() >= maxCostMicros;
    }

    private void markExhausted(String reason) {
        if (exhaustionReason == null) exhaustionReason = reason;
    }

    private boolean toolDurationExhausted() {
        return usedToolDurationNanos.get() >= maxToolDurationNanos;
    }

    private static long toMicros(BigDecimal value) {
        return value.movePointRight(6).longValueExact();
    }
}
