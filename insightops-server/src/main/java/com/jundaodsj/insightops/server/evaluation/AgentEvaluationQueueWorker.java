package com.jundaodsj.insightops.server.evaluation;

import com.jundaodsj.insightops.agent.application.AgentEvaluationStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class AgentEvaluationQueueWorker {

    private final AgentEvaluationStore store;
    private final AgentEvaluationService service;
    private final AgentEvaluationQueueProperties properties;
    private final Executor executor;
    private final AgentEvaluationMetrics metrics;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final String workerId;

    public AgentEvaluationQueueWorker(
            AgentEvaluationStore store,
            AgentEvaluationService service,
            AgentEvaluationQueueProperties properties,
            @Qualifier("agentEvaluationExecutor") Executor executor,
            AgentEvaluationMetrics metrics) {
        this(store, service, properties, executor, metrics,
                "server-" + UUID.randomUUID().toString().substring(0, 12));
    }

    AgentEvaluationQueueWorker(
            AgentEvaluationStore store,
            AgentEvaluationService service,
            AgentEvaluationQueueProperties properties,
            Executor executor,
            AgentEvaluationMetrics metrics,
            String workerId) {
        this.store = store;
        this.service = service;
        this.properties = properties;
        this.executor = executor;
        this.metrics = metrics;
        this.workerId = workerId;
    }

    @Scheduled(
            initialDelayString = "${insightops.agent.evaluation-queue.initial-delay-ms:1000}",
            fixedDelayString = "${insightops.agent.evaluation-queue.poll-interval-ms:2000}")
    public void poll() {
        if (!properties.isEnabled()) return;
        int capacity = properties.safeConcurrency() - inFlight.get();
        if (capacity <= 0) return;
        List<AgentEvaluationStore.EvaluationLease> leases = store.claimEvaluations(
                workerId, capacity, properties.safeMaxAttempts(),
                properties.leaseDuration(), Instant.now());
        for (AgentEvaluationStore.EvaluationLease lease : leases) {
            inFlight.incrementAndGet();
            metrics.claimed(lease.attemptCount() > 1);
            try {
                executor.execute(() -> {
                    try {
                        service.executeClaim(lease);
                    }
                    finally {
                        inFlight.updateAndGet(value -> Math.max(0, value - 1));
                    }
                });
            }
            catch (RuntimeException exception) {
                inFlight.updateAndGet(value -> Math.max(0, value - 1));
                metrics.dispatchError();
            }
        }
    }

    int inFlight() { return inFlight.get(); }
    String workerId() { return workerId; }
}
