package com.jundaodsj.insightops.server.chat;

import com.jundaodsj.insightops.conversation.application.DurableChatRunStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class DurableChatRunQueueWorker {

    private final DurableChatRunStore store;
    private final DurableChatRunExecutionService service;
    private final DurableChatRunProperties properties;
    private final Executor executor;
    private final DurableChatRunMetrics metrics;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final String workerId;

    @Autowired
    public DurableChatRunQueueWorker(
            DurableChatRunStore store,
            DurableChatRunExecutionService service,
            DurableChatRunProperties properties,
            @Qualifier("durableChatRunExecutor") Executor executor,
            DurableChatRunMetrics metrics) {
        this(store, service, properties, executor, metrics,
                "chat-server-" + UUID.randomUUID().toString().substring(0, 12));
    }

    DurableChatRunQueueWorker(
            DurableChatRunStore store,
            DurableChatRunExecutionService service,
            DurableChatRunProperties properties,
            Executor executor,
            DurableChatRunMetrics metrics,
            String workerId) {
        this.store = store;
        this.service = service;
        this.properties = properties;
        this.executor = executor;
        this.metrics = metrics;
        this.workerId = workerId;
    }

    @Scheduled(
            initialDelayString = "${insightops.agent.chat-queue.initial-delay-ms:1000}",
            fixedDelayString = "${insightops.agent.chat-queue.poll-interval-ms:500}")
    public void poll() {
        if (!properties.isEnabled()) return;
        int capacity = properties.safeConcurrency() - inFlight.get();
        if (capacity <= 0) return;
        List<DurableChatRunStore.WorkLease> leases = store.claim(
                workerId, capacity, properties.safeMaxAttempts(),
                properties.leaseDuration(), Instant.now());
        for (DurableChatRunStore.WorkLease lease : leases) {
            inFlight.incrementAndGet();
            metrics.claimed(lease.reclaimed());
            try {
                executor.execute(() -> {
                    try { service.execute(lease); }
                    finally { inFlight.updateAndGet(value -> Math.max(0, value - 1)); }
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
