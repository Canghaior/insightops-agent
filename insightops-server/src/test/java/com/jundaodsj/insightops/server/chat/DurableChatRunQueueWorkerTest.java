package com.jundaodsj.insightops.server.chat;

import com.jundaodsj.insightops.conversation.application.DurableChatRunStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DurableChatRunQueueWorkerTest {

    @Test
    void springCanSelectTheProductionConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DurableChatRunStore.class, () -> mock(DurableChatRunStore.class));
            context.registerBean(DurableChatRunExecutionService.class,
                    () -> mock(DurableChatRunExecutionService.class));
            context.registerBean(DurableChatRunProperties.class, DurableChatRunProperties::new);
            context.registerBean("durableChatRunExecutor", Executor.class, () -> Runnable::run);
            context.registerBean(DurableChatRunMetrics.class,
                    () -> new DurableChatRunMetrics(new SimpleMeterRegistry()));
            context.register(DurableChatRunQueueWorker.class);

            context.refresh();

            assertThat(context.getBean(DurableChatRunQueueWorker.class)).isNotNull();
        }
    }

    @Test
    void claimsReclaimedChatRunWithinCapacityAndDispatchesIt() {
        DurableChatRunStore store = mock(DurableChatRunStore.class);
        DurableChatRunExecutionService service = mock(DurableChatRunExecutionService.class);
        DurableChatRunProperties properties = new DurableChatRunProperties();
        properties.setConcurrency(2);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DurableChatRunStore.WorkLease lease = lease(true);
        when(store.claim(eq("chat-worker"), eq(2), eq(3), any(), any()))
                .thenReturn(List.of(lease));
        DurableChatRunQueueWorker worker = new DurableChatRunQueueWorker(
                store, service, properties, Runnable::run,
                new DurableChatRunMetrics(registry), "chat-worker");

        worker.poll();

        verify(service).execute(lease);
        assertThat(worker.inFlight()).isZero();
        assertThat(registry.counter("insightops.agent.chat.queue.claimed").count()).isEqualTo(1);
        assertThat(registry.counter("insightops.agent.chat.queue.reclaimed").count()).isEqualTo(1);
    }

    @Test
    void doesNotClaimWhenDisabledAndRestoresCapacityAfterRejectedDispatch() {
        DurableChatRunStore store = mock(DurableChatRunStore.class);
        DurableChatRunExecutionService service = mock(DurableChatRunExecutionService.class);
        DurableChatRunProperties properties = new DurableChatRunProperties();
        properties.setEnabled(false);
        DurableChatRunQueueWorker disabled = new DurableChatRunQueueWorker(
                store, service, properties, Runnable::run,
                new DurableChatRunMetrics(new SimpleMeterRegistry()), "disabled");

        disabled.poll();

        verify(store, never()).claim(any(), anyInt(), anyInt(), any(), any());

        properties.setEnabled(true);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DurableChatRunStore.WorkLease lease = lease(false);
        when(store.claim(eq("rejecting"), eq(2), eq(3), any(), any()))
                .thenReturn(List.of(lease));
        Executor rejecting = command -> { throw new RejectedExecutionException("full"); };
        DurableChatRunQueueWorker worker = new DurableChatRunQueueWorker(
                store, service, properties, rejecting,
                new DurableChatRunMetrics(registry), "rejecting");

        worker.poll();

        assertThat(worker.inFlight()).isZero();
        assertThat(registry.counter("insightops.agent.chat.queue.dispatch_errors").count())
                .isEqualTo(1);
        verify(service, never()).execute(any());
    }

    private static DurableChatRunStore.WorkLease lease(boolean reclaimed) {
        return new DurableChatRunStore.WorkLease(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "trace-chat-worker", true, "SYSTEM_ADMIN", "question", "context",
                null, null, UUID.randomUUID(), "chat-worker", reclaimed ? 2 : 1, 3,
                reclaimed, Instant.now().plusSeconds(120));
    }
}
