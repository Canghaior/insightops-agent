package com.jundaodsj.insightops.server.chat;

import com.jundaodsj.insightops.conversation.application.DurableChatRunStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class DurableChatRunMetrics {

    private final Counter claimed;
    private final Counter reclaimed;
    private final Counter recovered;
    private final Counter leaseLost;
    private final Counter cancelled;
    private final Counter dispatchErrors;
    private final Counter snapshotErrors;
    private final Counter streamConnections;
    private final Counter streamReconnects;
    private final Counter replayedEvents;
    private final Counter streamDisconnects;
    private final Timer reclaimDelay;
    private final AtomicLong queued = new AtomicLong();
    private final AtomicLong running = new AtomicLong();
    private final AtomicLong expiredLeases = new AtomicLong();
    private final AtomicLong oldestQueuedAgeSeconds = new AtomicLong();
    private final AtomicLong oldestHeartbeatAgeSeconds = new AtomicLong();

    public DurableChatRunMetrics(MeterRegistry registry) {
        claimed = registry.counter("insightops.agent.chat.queue.claimed");
        reclaimed = registry.counter("insightops.agent.chat.queue.reclaimed");
        recovered = registry.counter("insightops.agent.chat.queue.recovered");
        leaseLost = registry.counter("insightops.agent.chat.queue.lease_lost");
        cancelled = registry.counter("insightops.agent.chat.queue.cancelled");
        dispatchErrors = registry.counter("insightops.agent.chat.queue.dispatch_errors");
        snapshotErrors = registry.counter("insightops.agent.chat.queue.snapshot_errors");
        streamConnections = registry.counter("insightops.agent.chat.stream.connections");
        streamReconnects = registry.counter("insightops.agent.chat.stream.reconnects");
        replayedEvents = registry.counter("insightops.agent.chat.stream.replayed_events");
        streamDisconnects = registry.counter("insightops.agent.chat.stream.disconnects");
        reclaimDelay = Timer.builder("insightops.agent.chat.queue.reclaim_delay")
                .description("Delay between lease expiry and durable chat run reclaim")
                .publishPercentileHistogram()
                .register(registry);
        registry.gauge("insightops.agent.chat.queue.queued", queued);
        registry.gauge("insightops.agent.chat.queue.running", running);
        registry.gauge("insightops.agent.chat.queue.expired_leases", expiredLeases);
        registry.gauge("insightops.agent.chat.queue.oldest_queued_age_seconds", oldestQueuedAgeSeconds);
        registry.gauge("insightops.agent.chat.queue.oldest_heartbeat_age_seconds", oldestHeartbeatAgeSeconds);
    }

    public void claimed(DurableChatRunStore.WorkLease lease) {
        claimed.increment();
        if (lease.reclaimed()) {
            reclaimed.increment();
            reclaimDelay.record(lease.reclaimDelay());
        }
    }
    public void recovered() { recovered.increment(); }
    public void leaseLost() { leaseLost.increment(); }
    public void cancelled() { cancelled.increment(); }
    public void dispatchError() { dispatchErrors.increment(); }
    public void snapshotError() { snapshotErrors.increment(); }

    public void streamOpened(boolean resumed) {
        streamConnections.increment();
        if (resumed) streamReconnects.increment();
    }

    public void replayed(int count) {
        if (count > 0) replayedEvents.increment(count);
    }

    public void streamDisconnected() { streamDisconnects.increment(); }

    public void snapshot(DurableChatRunStore.QueueSnapshot value) {
        queued.set(value.queued());
        running.set(value.running());
        expiredLeases.set(value.expiredLeases());
        oldestQueuedAgeSeconds.set(value.oldestQueuedAgeSeconds());
        oldestHeartbeatAgeSeconds.set(value.oldestHeartbeatAgeSeconds());
    }
}
