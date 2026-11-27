package com.jundaodsj.insightops.server.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.agent.application.AgentWorkflowRunStore;
import com.jundaodsj.insightops.businesssystem.application.AssessmentType;
import com.jundaodsj.insightops.conversation.application.DurableChatRunStore;
import com.jundaodsj.insightops.infrastructure.config.DeepSeekModelProperties;
import com.jundaodsj.insightops.memory.application.UserMemoryStore;
import com.jundaodsj.insightops.model.application.ChatModelRequest;
import com.jundaodsj.insightops.model.application.ChatStreamEvent;
import com.jundaodsj.insightops.model.application.ChatStreamListener;
import com.jundaodsj.insightops.model.application.ChatStreamSession;
import com.jundaodsj.insightops.model.application.ModelUsage;
import com.jundaodsj.insightops.model.application.StreamingChatModelGateway;
import com.jundaodsj.insightops.server.workflow.AgentWorkflowFixedGraphService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        DurableChatRunStore.WorkLease lease = lease("context");
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

    @ParameterizedTest
    @EnumSource(AssessmentType.class)
    void gatewayReceivesScopeRulesForBothAssessments(AssessmentType assessmentType) {
        var lease = lease("所选基线 v1 · evidence/application.log:17 · 应用进程 JDK 21");
        var store = mock(DurableChatRunStore.class);
        when(store.renewLease(eq(lease.runId()), eq(lease.leaseToken()), any(), any()))
                .thenReturn(DurableChatRunStore.LeaseControl.ACTIVE);
        when(store.prepareAttempt(eq(lease.runId()), eq(lease.leaseToken()), any()))
                .thenReturn(new DurableChatRunStore.AttemptPreparation(null, false));

        var workflow = mock(AgentWorkflowRunStore.WorkflowRun.class);
        var graphs = mock(AgentWorkflowFixedGraphService.class);
        when(graphs.findWorkflow(lease.runId())).thenReturn(Optional.of(workflow));
        when(graphs.linkedSystemInputs(workflow)).thenReturn(Map.of(
                "assessmentType", assessmentType.name(), "engineeringBaseline", Map.of("version", 1)));
        var usage = new ModelUsage(0, 0, 0, null, null);
        when(graphs.execute(eq(workflow), any(), any(), any())).thenReturn(new AgentLoopService.LoopResult(
                "", List.of(), List.of(), usage, 0, false, null, null));

        var gateway = mock(StreamingChatModelGateway.class);
        when(gateway.stream(any(), any())).thenAnswer(call -> {
            ChatStreamListener listener = call.getArgument(1);
            listener.onEvent(ChatStreamEvent.completed("fixture", "fixture", usage, Duration.ZERO, Duration.ZERO));
            return mock(ChatStreamSession.class);
        });
        var terminal = mock(DurableChatRunTerminalService.class);
        when(terminal.succeed(eq(lease), anyString(), anyString(), anyString(), any(), any(), anyString(), any()))
                .thenReturn(true);

        try (ExecutorService attempts = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
             ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor()) {
            var service = new DurableChatRunExecutionService(store, mock(AgentLoopService.class), gateway,
                    new DeepSeekModelProperties(true, "https://api.deepseek.com", "deepseek-v4-flash",
                            false, 0.2, 4096, 4, 90, 2, false),
                    new P0ChatGuardrail(), mock(UserMemoryStore.class), terminal, new DurableChatRunProperties(),
                    heartbeats, attempts, new DurableChatRunMetrics(new SimpleMeterRegistry()),
                    new ObjectMapper().findAndRegisterModules());
            ReflectionTestUtils.setField(service, "workflowGraphs", graphs);
            service.execute(lease);
        }

        var request = ArgumentCaptor.forClass(ChatModelRequest.class);
        verify(gateway).stream(request.capture(), any());
        assertThat(request.getValue().userPrompt()).isEqualTo(lease.contextualPrompt());
        assertThat(request.getValue().systemPrompt()).contains(
                "受支持事实的摘要", "本次摘要未覆盖", "不得据此认定完整原件没有记录",
                "未读取、未解析、未传入与经核对不存在", "Maven POM Profile", "Spring 应用运行 Profile",
                "单次数据库连接观察", "数据源配置覆盖情况", "与当前目标环境的对应关系需要另行核对");
    }

    private static DurableChatRunStore.WorkLease lease(String contextualPrompt) {
        return new DurableChatRunStore.WorkLease(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "trace-timeout",
                true,
                "SYSTEM_ADMIN",
                "question",
                contextualPrompt,
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
