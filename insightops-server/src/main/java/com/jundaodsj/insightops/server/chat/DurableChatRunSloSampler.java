package com.jundaodsj.insightops.server.chat;

import com.jundaodsj.insightops.conversation.application.DurableChatRunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class DurableChatRunSloSampler {

    private static final Logger LOGGER = LoggerFactory.getLogger(DurableChatRunSloSampler.class);

    private final DurableChatRunStore store;
    private final DurableChatRunMetrics metrics;

    public DurableChatRunSloSampler(DurableChatRunStore store, DurableChatRunMetrics metrics) {
        this.store = store;
        this.metrics = metrics;
    }

    @Scheduled(
            initialDelayString = "${insightops.agent.chat-queue.snapshot-interval-ms:15000}",
            fixedDelayString = "${insightops.agent.chat-queue.snapshot-interval-ms:15000}")
    public void sample() {
        try { metrics.snapshot(store.queueSnapshot(Instant.now())); }
        catch (RuntimeException exception) {
            metrics.snapshotError();
            LOGGER.warn("Failed to sample durable chat queue SLO metrics", exception);
        }
    }
}
