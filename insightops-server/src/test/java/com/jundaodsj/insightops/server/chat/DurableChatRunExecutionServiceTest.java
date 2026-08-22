package com.jundaodsj.insightops.server.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.conversation.application.DurableChatRunStore;
import com.jundaodsj.insightops.infrastructure.config.DeepSeekModelProperties;
import com.jundaodsj.insightops.memory.application.UserMemoryStore;
import com.jundaodsj.insightops.model.application.StreamingChatModelGateway;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DurableChatRunExecutionServiceTest {

    @Test
    void totalTimeoutFailsTheRunAndReleasesTheWorkerSlot() {
        DurableChatRunStore store = mock(DurableChatRunStore.class);
        AgentLoopService agentLoop = mock(AgentLoopService.class);
        DurableChatRunTerminalService terminal = mock(DurableChatRunTerminalService.class);
        DurableChatRunStore.WorkLease lease = lease();
        DurableChatRunProperties properties = new DurableChatRunProperties();
        properties.setRunTimeoutSeconds(1);
        when(store.renewLease(
                eq(lease.runId()), eq(lease.leaseToken()), any(), any()))
                .thenReturn(DurableChatRunStore.LeaseControl.ACTIVE);
        when(store.prepareAttempt(eq(lease.runId()), eq(lease.leaseToken()), any()))
                .thenReturn(new DurableChatRunStore.AttemptPreparation(null, false));
        when(agentLoop.run(any(), any(), any())).thenAnswer(invocation -> {
            try {
                Thread.sleep(10_000);
                return null;
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AgentLoopService.AgentLoopException("CANCELLED", exception);
            }
        });
        when(terminal.fail(
                eq(lease), eq(""), eq("TIMED_OUT"), anyString(), any()))
                .thenReturn(true);

        try (ExecutorService attempts = Executors.newThreadPerTaskExecutor(
                     Thread.ofVirtual().factory());
             ScheduledExecutorService heartbeats =
                     Executors.newSingleThreadScheduledExecutor()) {
            DurableChatRunExecutionService service = new DurableChatRunExecutionService(
                    store,
                    agentLoop,
                    mock(StreamingChatModelGateway.class),
                    new DeepSeekModelProperties(
                            true, "https://api.deepseek.com", "deepseek-v4-flash",
                            false, 0.2, 4096, 4, 90, 2, false),
                    mock(P0ChatGuardrail.class),
                    mock(UserMemoryStore.class),
                    terminal,
                    properties,
                    heartbeats,
                    attempts,
                    new DurableChatRunMetrics(new SimpleMeterRegistry()),
                    new ObjectMapper().findAndRegisterModules());
            Instant started = Instant.now();

            service.execute(lease);

            assertThat(Duration.between(started, Instant.now()))
                    .isLessThan(Duration.ofSeconds(3));
        }
        verify(terminal).fail(
                eq(lease), eq(""), eq("TIMED_OUT"), anyString(), any());
        verify(agentLoop).releaseCost(lease.runId(), "TIMED_OUT");
    }

    private static DurableChatRunStore.WorkLease lease() {
        return new DurableChatRunStore.WorkLease(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "trace-timeout",
                true,
                "SYSTEM_ADMIN",
                "question",
                "context",
                null,
                null,
                UUID.randomUUID(),
                "chat-worker",
                1,
                3,
                false,
                Duration.ZERO,
                Instant.now().plusSeconds(120));
    }
}
