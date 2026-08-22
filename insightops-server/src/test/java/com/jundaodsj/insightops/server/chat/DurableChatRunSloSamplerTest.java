package com.jundaodsj.insightops.server.chat;

import com.jundaodsj.insightops.conversation.application.DurableChatRunStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DurableChatRunSloSamplerTest {

    @Test
    void publishesQueueAndRecoverySloGauges() {
        DurableChatRunStore store = mock(DurableChatRunStore.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DurableChatRunMetrics metrics = new DurableChatRunMetrics(registry);
        when(store.queueSnapshot(any())).thenReturn(
                new DurableChatRunStore.QueueSnapshot(7, 2, 1, 45, 20));

        new DurableChatRunSloSampler(store, metrics).sample();

        assertThat(gauge(registry, "insightops.agent.chat.queue.queued")).isEqualTo(7);
        assertThat(gauge(registry, "insightops.agent.chat.queue.running")).isEqualTo(2);
        assertThat(gauge(registry, "insightops.agent.chat.queue.expired_leases")).isEqualTo(1);
        assertThat(gauge(registry, "insightops.agent.chat.queue.oldest_queued_age_seconds"))
                .isEqualTo(45);
        assertThat(gauge(registry, "insightops.agent.chat.queue.oldest_heartbeat_age_seconds"))
                .isEqualTo(20);
    }

    @Test
    void recordsSnapshotFailuresWithoutStoppingTheScheduler() {
        DurableChatRunStore store = mock(DurableChatRunStore.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DurableChatRunMetrics metrics = new DurableChatRunMetrics(registry);
        when(store.queueSnapshot(any())).thenThrow(new IllegalStateException("database unavailable"));

        new DurableChatRunSloSampler(store, metrics).sample();

        assertThat(registry.counter("insightops.agent.chat.queue.snapshot_errors").count())
                .isEqualTo(1);
    }

    @Test
    void recordsStreamResumeReplayAndDisconnect() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DurableChatRunMetrics metrics = new DurableChatRunMetrics(registry);

        metrics.streamOpened(true);
        metrics.replayed(3);
        metrics.streamDisconnected();

        assertThat(registry.counter("insightops.agent.chat.stream.connections").count())
                .isEqualTo(1);
        assertThat(registry.counter("insightops.agent.chat.stream.reconnects").count())
                .isEqualTo(1);
        assertThat(registry.counter("insightops.agent.chat.stream.replayed_events").count())
                .isEqualTo(3);
        assertThat(registry.counter("insightops.agent.chat.stream.disconnects").count())
                .isEqualTo(1);
    }

    private static double gauge(SimpleMeterRegistry registry, String name) {
        return registry.get(name).gauge().value();
    }
}
