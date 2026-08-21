package com.jundaodsj.insightops.server.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.agent.application.AgentLoopAuditStore;
import com.jundaodsj.insightops.agent.application.AgentOrchestrationStore;
import com.jundaodsj.insightops.conversation.application.ChatCitation;
import com.jundaodsj.insightops.infrastructure.config.DeepSeekModelProperties;
import com.jundaodsj.insightops.infrastructure.config.DeepSeekPricingProperties;
import com.jundaodsj.insightops.infrastructure.model.DeepSeekCostEstimator;
import com.jundaodsj.insightops.model.application.AgentPlanningModelGateway;
import com.jundaodsj.insightops.model.application.ModelUsage;
import com.jundaodsj.insightops.project.application.AdminProjectStore;
import com.jundaodsj.insightops.server.tool.AgentToolResilienceProperties;
import com.jundaodsj.insightops.tool.application.McpConnectionStore;
import com.jundaodsj.insightops.tool.application.registry.AgentToolDefinition;
import com.jundaodsj.insightops.tool.application.registry.AgentToolRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentLoopServiceTest {

    @Test
    void shouldPlanExecuteObserveAndFinishWithAccumulatedUsage() {
        AgentPlanningModelGateway planning = mock(AgentPlanningModelGateway.class);
        AgentToolDispatcher dispatcher = mock(AgentToolDispatcher.class);
        AgentLoopAuditStore audit = mock(AgentLoopAuditStore.class);
        AdminProjectStore projects = mock(AdminProjectStore.class);
        when(projects.list(any())).thenReturn(List.of());
        var call = new AgentPlanningModelGateway.PlannedToolCall(
                "call-1", "test_search", "{\"query\":\"Spring AI\"}");
        when(planning.plan(any()))
                .thenReturn(plan("", List.of(call), new ModelUsage(10, 3, 13, null, null)))
                .thenReturn(plan("FINISH", List.of(), new ModelUsage(6, 2, 8, null, null)));
        UUID toolCallId = UUID.randomUUID();
        when(dispatcher.execute(any(), anyString(), any(), any(), any())).thenReturn(
                new AgentToolDispatcher.ExecutionResult(
                        toolCallId, "test_search", Map.of("answer", "official"),
                        "\n[S1] official evidence\n", List.of("https://example.com/official"),
                        List.of(new ChatCitation(
                                "S1", "Official", "https://example.com/official",
                                "project", "heading", "OFFICIAL_DOCUMENT", 0.9)),
                        1, "bge-m3"));
        AgentLoopService service = service(planning, dispatcher, audit, projects, 4);

        AgentLoopService.LoopResult result = service.run(request(), mockListener(), () -> true);

        assertThat(result.toolRounds()).isEqualTo(1);
        assertThat(result.limitReached()).isFalse();
        assertThat(result.planningUsage()).isEqualTo(new ModelUsage(16, 5, 21, null, null));
        assertThat(result.systemPromptAppendix()).contains("official evidence");
        assertThat(result.sourceUrls()).containsExactly("https://example.com/official");
        assertThat(result.citations()).extracting(ChatCitation::label).containsExactly("S1");
        assertThat(result.planId()).isNotNull();
        assertThat(result.budget().status()).isEqualTo("CLOSED");
        ArgumentCaptor<AgentPlanningModelGateway.AgentPlanRequest> plans =
                ArgumentCaptor.forClass(AgentPlanningModelGateway.AgentPlanRequest.class);
        verify(planning, times(2)).plan(plans.capture());
        assertThat(plans.getAllValues().get(1).exchanges()).hasSize(1);
        assertThat(plans.getAllValues().get(1).exchanges().getFirst().responseJson())
                .contains("official");
        verify(dispatcher).execute(any(), anyString(), any(), any(), any());
        verify(audit, times(3)).recordStep(
                any(), any(), anyInt(), anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void shouldStopAtConfiguredToolRoundLimit() {
        AgentPlanningModelGateway planning = mock(AgentPlanningModelGateway.class);
        AgentToolDispatcher dispatcher = mock(AgentToolDispatcher.class);
        AgentLoopAuditStore audit = mock(AgentLoopAuditStore.class);
        AdminProjectStore projects = mock(AdminProjectStore.class);
        when(projects.list(any())).thenReturn(List.of());
        when(planning.plan(any()))
                .thenReturn(plan("", List.of(new AgentPlanningModelGateway.PlannedToolCall(
                        "call-1", "test_search", "{\"query\":\"one\"}")), ModelUsage.unknown()))
                .thenReturn(plan("", List.of(new AgentPlanningModelGateway.PlannedToolCall(
                        "call-2", "test_search", "{\"query\":\"two\"}")), ModelUsage.unknown()));
        when(dispatcher.execute(any(), anyString(), any(), any(), any())).thenAnswer(invocation -> {
            AgentToolDispatcher.ExecutionContext context = invocation.getArgument(0);
            return new AgentToolDispatcher.ExecutionResult(
                    UUID.randomUUID(), "test_search", Map.of("round", context.round()),
                    "evidence-" + context.round(), List.of(), List.of(), 1, null);
        });
        AgentLoopService service = service(planning, dispatcher, audit, projects, 2);

        AgentLoopService.LoopResult result = service.run(request(), mockListener(), () -> true);

        assertThat(result.toolRounds()).isEqualTo(2);
        assertThat(result.limitReached()).isTrue();
        assertThat(result.systemPromptAppendix()).contains("安全轮次上限");
        assertThat(result.budget().exhaustionReason()).isEqualTo("MAX_PLANNING_ROUNDS");
        verify(planning, times(2)).plan(any());
        verify(dispatcher, times(2)).execute(any(), anyString(), any(), any(), any());
    }

    @Test
    void shouldExecuteIndependentReadOnlyCallsInParallelLayer() {
        AgentPlanningModelGateway planning = mock(AgentPlanningModelGateway.class);
        AgentToolDispatcher dispatcher = mock(AgentToolDispatcher.class);
        AgentLoopAuditStore audit = mock(AgentLoopAuditStore.class);
        AdminProjectStore projects = mock(AdminProjectStore.class);
        when(projects.list(any())).thenReturn(List.of());
        var first = new AgentPlanningModelGateway.PlannedToolCall(
                "call-release", "test_search", "{\"query\":\"release\"}");
        var second = new AgentPlanningModelGateway.PlannedToolCall(
                "call-docs", "second_search", "{\"query\":\"docs\"}");
        when(planning.plan(any()))
                .thenReturn(plan("", List.of(first, second), ModelUsage.unknown()))
                .thenReturn(plan("FINISH", List.of(), ModelUsage.unknown()));
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        when(dispatcher.execute(any(), anyString(), any(), any(), any())).thenAnswer(invocation -> {
            int current = active.incrementAndGet();
            maximumActive.accumulateAndGet(current, Math::max);
            try {
                Thread.sleep(80);
            }
            finally {
                active.decrementAndGet();
            }
            String toolName = invocation.getArgument(1);
            return new AgentToolDispatcher.ExecutionResult(
                    UUID.randomUUID(), toolName, Map.of("answer", toolName),
                    "evidence-" + toolName, List.of(), List.of(), 1, null);
        });
        AgentLoopService service = service(planning, dispatcher, audit, projects, 4);

        AgentLoopService.LoopResult result = service.run(request(), mockListener(), () -> true);

        assertThat(result.toolRounds()).isEqualTo(2);
        assertThat(result.limitReached()).isFalse();
        assertThat(maximumActive.get()).isGreaterThanOrEqualTo(2);
        ArgumentCaptor<String> toolNames = ArgumentCaptor.forClass(String.class);
        verify(dispatcher, times(2)).execute(any(), toolNames.capture(), any(), any(), any());
        assertThat(toolNames.getAllValues()).containsExactlyInAnyOrder("test_search", "second_search");
        ArgumentCaptor<AgentPlanningModelGateway.AgentPlanRequest> plans =
                ArgumentCaptor.forClass(AgentPlanningModelGateway.AgentPlanRequest.class);
        verify(planning, times(2)).plan(plans.capture());
        assertThat(plans.getAllValues().get(1).exchanges()).hasSize(2);
    }

    @Test
    void shouldStopPlanningWhenMutatingNodeWaitsForApproval() {
        AgentPlanningModelGateway planning = mock(AgentPlanningModelGateway.class);
        AgentToolDispatcher dispatcher = mock(AgentToolDispatcher.class);
        AgentLoopAuditStore audit = mock(AgentLoopAuditStore.class);
        AdminProjectStore projects = mock(AdminProjectStore.class);
        when(projects.list(any())).thenReturn(List.of());
        var call = new AgentPlanningModelGateway.PlannedToolCall(
                "call-memory", "memory_write", "{\"query\":\"remember\"}");
        when(planning.plan(any())).thenReturn(plan("", List.of(call), ModelUsage.unknown()));
        when(dispatcher.execute(any(), anyString(), any(), any(), any())).thenReturn(
                new AgentToolDispatcher.ExecutionResult(
                        UUID.randomUUID(), "memory_write", Map.of("status", "WAITING_APPROVAL"),
                        "waiting approval", List.of(), List.of(), 0, "human-approval"));
        AgentLoopService service = service(planning, dispatcher, audit, projects, 4);

        AgentLoopService.LoopResult result = service.run(request(), mockListener(), () -> true);

        assertThat(result.limitReached()).isFalse();
        assertThat(result.toolRounds()).isEqualTo(1);
        verify(planning).plan(any());
    }

    private static AgentLoopService service(
            AgentPlanningModelGateway planning,
            AgentToolDispatcher dispatcher,
            AgentLoopAuditStore audit,
            AdminProjectStore projects,
            int maxRounds) {
        return new AgentLoopService(
                planning, dispatcher,
                new AgentToolRegistry(List.of(tool(), secondTool(), memoryTool())), audit,
                orchestrationStore(), projects, emptyMcpStore(),
                new DeepSeekModelProperties(
                        true, "https://api.deepseek.com", "deepseek-v4-flash", false,
                        0.2, 4096, maxRounds, 90, 2, false),
                new ObjectMapper(), new AgentToolResilienceProperties(),
                new AgentOrchestrationProperties(),
                new DeepSeekCostEstimator(new DeepSeekPricingProperties(
                        LocalDate.parse("2026-08-16"), new BigDecimal("7.20"),
                        new BigDecimal("0.0028"), new BigDecimal("0.14"),
                        new BigDecimal("0.28"))),
                new AgentOrchestrationMetrics(new SimpleMeterRegistry()));
    }

    private static AgentOrchestrationStore orchestrationStore() {
        AgentOrchestrationStore store = mock(AgentOrchestrationStore.class);
        when(store.startRun(any(), any(), any()))
                .thenReturn(new AgentOrchestrationStore.PlanHandle(UUID.randomUUID(), 1));
        when(store.appendLayer(any(), any(), anyInt(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    int round = invocation.getArgument(2);
                    List<AgentOrchestrationStore.NodeDraft> drafts = invocation.getArgument(3);
                    List<UUID> dependencies = invocation.getArgument(4);
                    return drafts.stream().map(draft -> new AgentOrchestrationStore.PlanNode(
                            draft.id(), round, draft.position(), draft.toolName(), "PENDING",
                            dependencies)).toList();
                });
        return store;
    }

    private static McpConnectionStore emptyMcpStore() {
        McpConnectionStore store = mock(McpConnectionStore.class);
        when(store.list(any())).thenReturn(List.of());
        return store;
    }

    private static AgentToolDefinition tool() {
        return definition("test_search", "Search test evidence");
    }

    private static AgentToolDefinition secondTool() {
        return definition("second_search", "Search secondary evidence");
    }

    private static AgentToolDefinition memoryTool() {
        return new AgentToolDefinition(
                "memory_write", 1, "Write memory after approval", true,
                AgentToolDefinition.AccessLevel.WORKSPACE_MEMBER,
                AgentToolDefinition.RiskLevel.MUTATING,
                AgentToolDefinition.ApprovalPolicy.REQUIRED,
                Duration.ofSeconds(10), 10_000,
                List.of(AgentToolDefinition.Parameter.string(
                        "query", "Memory value", true, 200)),
                List.of(AgentToolDefinition.Parameter.string(
                        "status", "Approval status", false, 100)));
    }

    private static AgentToolDefinition definition(String name, String description) {
        return new AgentToolDefinition(
                name, 1, description, true,
                AgentToolDefinition.AccessLevel.WORKSPACE_MEMBER,
                AgentToolDefinition.RiskLevel.READ_ONLY,
                AgentToolDefinition.ApprovalPolicy.NOT_REQUIRED,
                Duration.ofSeconds(10), 10_000,
                List.of(AgentToolDefinition.Parameter.string(
                        "query", "Search query", true, 200)),
                List.of(AgentToolDefinition.Parameter.string(
                        "answer", "Search answer", false, 2_000)));
    }

    private static AgentPlanningModelGateway.AgentPlanResponse plan(
            String content,
            List<AgentPlanningModelGateway.PlannedToolCall> calls,
            ModelUsage usage) {
        return new AgentPlanningModelGateway.AgentPlanResponse(
                content, calls, "deepseek", "deepseek-v4-flash", usage, Duration.ofMillis(5));
    }

    private static AgentLoopService.LoopRequest request() {
        return new AgentLoopService.LoopRequest(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), false,
                AgentToolDefinition.AccessLevel.WORKSPACE_MEMBER, "Find official evidence");
    }

    private static AgentToolDispatcher.ProgressListener mockListener() {
        return mock(AgentToolDispatcher.ProgressListener.class);
    }
}
