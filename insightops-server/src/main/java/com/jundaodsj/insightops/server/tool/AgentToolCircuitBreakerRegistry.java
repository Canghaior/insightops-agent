package com.jundaodsj.insightops.server.tool;

import com.jundaodsj.insightops.tool.application.registry.AgentToolNames;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentToolCircuitBreakerRegistry {

    private final AgentToolResilienceProperties properties;
    private final AgentToolOperationalMetrics metrics;
    private final Map<String, Breaker> breakers = new ConcurrentHashMap<>();

    public AgentToolCircuitBreakerRegistry(
            AgentToolResilienceProperties properties,
            AgentToolOperationalMetrics metrics) {
        this.properties = properties;
        this.metrics = metrics;
    }

    public Permit acquire(String toolName) {
        String group = group(toolName);
        Breaker breaker = breakers.computeIfAbsent(group, this::newBreaker);
        return new Permit(group, breaker, breaker.acquire());
    }

    private Breaker newBreaker(String group) {
        Breaker breaker = new Breaker(properties);
        metrics.registerCircuitState(group, breaker::stateCode);
        return breaker;
    }

    static String group(String toolName) {
        return switch (toolName) {
            case AgentToolNames.GITHUB_RELEASE_LIST -> "github";
            case AgentToolNames.KNOWLEDGE_HYBRID_SEARCH -> "knowledge";
            case AgentToolNames.PROJECT_INTELLIGENCE_EVENT_SEARCH -> "database";
            default -> toolName;
        };
    }

    public record Permit(String group, Breaker breaker, boolean allowed) {
        public void success() { breaker.success(); }
        public void failure() { breaker.failure(); }
        public void ignored() { breaker.ignored(); }
    }

    static final class Breaker {
        private enum State { CLOSED, HALF_OPEN, OPEN }

        private final int windowSize;
        private final int minimumCalls;
        private final int failureRatePercent;
        private final int halfOpenPermits;
        private final Duration openDuration;
        private final ArrayDeque<Boolean> outcomes = new ArrayDeque<>();
        private State state = State.CLOSED;
        private Instant openUntil = Instant.EPOCH;
        private int halfOpenInFlight;
        private int halfOpenSucceeded;

        private Breaker(AgentToolResilienceProperties properties) {
            this.windowSize = Math.max(2, properties.getCircuitWindowSize());
            this.minimumCalls = Math.max(1,
                    Math.min(windowSize, properties.getCircuitMinimumCalls()));
            this.failureRatePercent = Math.max(1,
                    Math.min(100, properties.getCircuitFailureRatePercent()));
            this.halfOpenPermits = Math.max(1, properties.getCircuitHalfOpenPermits());
            this.openDuration = Duration.ofSeconds(
                    Math.max(1, properties.getCircuitOpenSeconds()));
        }

        synchronized boolean acquire() {
            if (state == State.OPEN && !Instant.now().isBefore(openUntil)) {
                state = State.HALF_OPEN;
                halfOpenInFlight = 0;
                halfOpenSucceeded = 0;
            }
            if (state == State.OPEN) return false;
            if (state == State.HALF_OPEN) {
                if (halfOpenInFlight >= halfOpenPermits) return false;
                halfOpenInFlight++;
            }
            return true;
        }

        synchronized void success() {
            if (state == State.HALF_OPEN) {
                halfOpenInFlight = Math.max(0, halfOpenInFlight - 1);
                halfOpenSucceeded++;
                if (halfOpenSucceeded >= halfOpenPermits) close();
                return;
            }
            if (state == State.CLOSED) record(false);
        }

        synchronized void failure() {
            if (state == State.HALF_OPEN) {
                open();
                return;
            }
            if (state == State.CLOSED) {
                record(true);
                long failures = outcomes.stream().filter(Boolean::booleanValue).count();
                if (outcomes.size() >= minimumCalls
                        && failures * 100 >= (long) outcomes.size() * failureRatePercent) {
                    open();
                }
            }
        }

        synchronized void ignored() {
            if (state == State.HALF_OPEN) {
                halfOpenInFlight = Math.max(0, halfOpenInFlight - 1);
            }
        }

        synchronized int stateCode() {
            if (state == State.OPEN && !Instant.now().isBefore(openUntil)) return 1;
            return switch (state) {
                case CLOSED -> 0;
                case HALF_OPEN -> 1;
                case OPEN -> 2;
            };
        }

        private void record(boolean failed) {
            outcomes.addLast(failed);
            while (outcomes.size() > windowSize) outcomes.removeFirst();
        }

        private void open() {
            state = State.OPEN;
            openUntil = Instant.now().plus(openDuration);
            halfOpenInFlight = 0;
            halfOpenSucceeded = 0;
        }

        private void close() {
            state = State.CLOSED;
            outcomes.clear();
            halfOpenInFlight = 0;
            halfOpenSucceeded = 0;
        }
    }
}
