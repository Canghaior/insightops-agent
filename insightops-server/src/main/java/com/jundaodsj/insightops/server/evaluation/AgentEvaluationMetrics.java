package com.jundaodsj.insightops.server.evaluation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class AgentEvaluationMetrics {

    private final Counter passed;
    private final Counter failed;
    private final Counter caseErrors;
    private final Counter claims;
    private final Counter reclaims;
    private final Counter leaseLost;
    private final Counter dispatchErrors;
    private final Timer duration;
    private final AtomicInteger running = new AtomicInteger();

    public AgentEvaluationMetrics(MeterRegistry registry) {
        passed = Counter.builder("insightops.agent.evaluation.runs")
                .tag("result", "passed").register(registry);
        failed = Counter.builder("insightops.agent.evaluation.runs")
                .tag("result", "failed").register(registry);
        caseErrors = Counter.builder("insightops.agent.evaluation.case.errors").register(registry);
        claims = Counter.builder("insightops.agent.evaluation.claims").register(registry);
        reclaims = Counter.builder("insightops.agent.evaluation.reclaims").register(registry);
        leaseLost = Counter.builder("insightops.agent.evaluation.lease.lost").register(registry);
        dispatchErrors = Counter.builder("insightops.agent.evaluation.dispatch.errors").register(registry);
        duration = Timer.builder("insightops.agent.evaluation.duration").register(registry);
        registry.gauge("insightops.agent.evaluation.running", running);
    }

    void started() {
        running.incrementAndGet();
    }

    void claimed(boolean reclaimed) {
        claims.increment();
        if (reclaimed) reclaims.increment();
    }

    void leaseLost() {
        leaseLost.increment();
    }

    void dispatchError() {
        dispatchErrors.increment();
    }

    void caseError() {
        caseErrors.increment();
    }

    void finished(boolean success, boolean terminal, Duration elapsed) {
        running.updateAndGet(value -> Math.max(0, value - 1));
        if (terminal) {
            (success ? passed : failed).increment();
            duration.record(elapsed);
        }
    }
}
