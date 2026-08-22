package com.jundaodsj.insightops.server.evaluation;

import com.jundaodsj.insightops.agent.application.AgentEvaluationStore;
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

class AgentEvaluationQueueWorkerTest {

    @Test
    void springCanSelectTheProductionConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AgentEvaluationStore.class, () -> mock(AgentEvaluationStore.class));
            context.registerBean(AgentEvaluationService.class, () -> mock(AgentEvaluationService.class));
            context.registerBean(AgentEvaluationQueueProperties.class, AgentEvaluationQueueProperties::new);
            context.registerBean("agentEvaluationExecutor", Executor.class, () -> Runnable::run);
            context.registerBean(AgentEvaluationMetrics.class,
                    () -> new AgentEvaluationMetrics(new SimpleMeterRegistry()));
            context.register(AgentEvaluationQueueWorker.class);

            context.refresh();

            assertThat(context.getBean(AgentEvaluationQueueWorker.class)).isNotNull();
        }
    }

    @Test
    void claimsExpiredEvaluationWithinCapacityAndDispatchesIt() {
        AgentEvaluationStore store = mock(AgentEvaluationStore.class);
        AgentEvaluationService service = mock(AgentEvaluationService.class);
        AgentEvaluationQueueProperties properties = new AgentEvaluationQueueProperties();
        properties.setConcurrency(2);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentEvaluationMetrics metrics = new AgentEvaluationMetrics(registry);
        Executor direct = Runnable::run;
        AgentEvaluationStore.EvaluationLease lease = new AgentEvaluationStore.EvaluationLease(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "worker-test", 2, Instant.now().plusSeconds(180));
        when(store.claimEvaluations(
                eq("worker-test"), eq(2), eq(3), any(), any())).thenReturn(List.of(lease));
        AgentEvaluationQueueWorker worker = new AgentEvaluationQueueWorker(
                store, service, properties, direct, metrics, "worker-test");

        worker.poll();

        verify(service).executeClaim(lease);
        assertThat(worker.inFlight()).isZero();
        assertThat(registry.counter("insightops.agent.evaluation.claims").count()).isEqualTo(1);
        assertThat(registry.counter("insightops.agent.evaluation.reclaims").count()).isEqualTo(1);
    }

    @Test
    void doesNotClaimWhenQueueIsDisabled() {
        AgentEvaluationStore store = mock(AgentEvaluationStore.class);
        AgentEvaluationService service = mock(AgentEvaluationService.class);
        AgentEvaluationQueueProperties properties = new AgentEvaluationQueueProperties();
        properties.setEnabled(false);
        AgentEvaluationQueueWorker worker = new AgentEvaluationQueueWorker(
                store, service, properties, Runnable::run,
                new AgentEvaluationMetrics(new SimpleMeterRegistry()), "worker-disabled");

        worker.poll();

        verify(store, never()).claimEvaluations(any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void restoresCapacityWhenExecutorRejectsDispatch() {
        AgentEvaluationStore store = mock(AgentEvaluationStore.class);
        AgentEvaluationService service = mock(AgentEvaluationService.class);
        AgentEvaluationQueueProperties properties = new AgentEvaluationQueueProperties();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentEvaluationStore.EvaluationLease lease = new AgentEvaluationStore.EvaluationLease(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "worker-rejected", 1, Instant.now().plusSeconds(180));
        when(store.claimEvaluations(
                eq("worker-rejected"), eq(1), eq(3), any(), any())).thenReturn(List.of(lease));
        Executor rejecting = command -> {
            throw new RejectedExecutionException("full");
        };
        AgentEvaluationQueueWorker worker = new AgentEvaluationQueueWorker(
                store, service, properties, rejecting,
                new AgentEvaluationMetrics(registry), "worker-rejected");

        worker.poll();

        assertThat(worker.inFlight()).isZero();
        assertThat(registry.counter(
                "insightops.agent.evaluation.dispatch.errors").count()).isEqualTo(1);
        verify(service, never()).executeClaim(any());
    }
}
