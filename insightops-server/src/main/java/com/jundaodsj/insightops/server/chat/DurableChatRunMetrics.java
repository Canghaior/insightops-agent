package com.jundaodsj.insightops.server.chat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class DurableChatRunMetrics {

    private final Counter claimed;
    private final Counter reclaimed;
    private final Counter recovered;
    private final Counter leaseLost;
    private final Counter cancelled;
    private final Counter dispatchErrors;

    public DurableChatRunMetrics(MeterRegistry registry) {
        claimed = registry.counter("insightops.agent.chat.queue.claimed");
        reclaimed = registry.counter("insightops.agent.chat.queue.reclaimed");
        recovered = registry.counter("insightops.agent.chat.queue.recovered");
        leaseLost = registry.counter("insightops.agent.chat.queue.lease_lost");
        cancelled = registry.counter("insightops.agent.chat.queue.cancelled");
        dispatchErrors = registry.counter("insightops.agent.chat.queue.dispatch_errors");
    }

    public void claimed(boolean takeover) { claimed.increment(); if (takeover) reclaimed.increment(); }
    public void recovered() { recovered.increment(); }
    public void leaseLost() { leaseLost.increment(); }
    public void cancelled() { cancelled.increment(); }
    public void dispatchError() { dispatchErrors.increment(); }
}
