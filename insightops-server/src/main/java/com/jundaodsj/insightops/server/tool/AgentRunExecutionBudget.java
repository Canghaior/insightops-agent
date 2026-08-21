package com.jundaodsj.insightops.server.tool;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class AgentRunExecutionBudget {

    private final Instant deadline;
    private final AtomicInteger remainingAttempts;
    private final long maxToolDurationNanos;
    private final AtomicLong usedToolDurationNanos = new AtomicLong();

    public AgentRunExecutionBudget(
            Duration runTimeout,
            int maxTotalAttempts,
            Duration maxToolDuration) {
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
        this.deadline = Instant.now().plus(runTimeout);
        this.remainingAttempts = new AtomicInteger(maxTotalAttempts);
        this.maxToolDurationNanos = maxToolDuration.toNanos();
    }

    public Duration remaining() {
        Duration value = Duration.between(Instant.now(), deadline);
        return value.isNegative() ? Duration.ZERO : value;
    }

    public boolean tryAcquireAttempt() {
        while (true) {
            int current = remainingAttempts.get();
            if (current <= 0 || remaining().isZero() || toolDurationExhausted()) return false;
            if (remainingAttempts.compareAndSet(current, current - 1)) return true;
        }
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

    private boolean toolDurationExhausted() {
        return usedToolDurationNanos.get() >= maxToolDurationNanos;
    }
}
