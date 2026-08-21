package com.jundaodsj.insightops.server.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.agent.application.AgentLoopAuditStore;
import com.jundaodsj.insightops.conversation.application.ChatCitation;
import com.jundaodsj.insightops.infrastructure.config.DeepSeekModelProperties;
import com.jundaodsj.insightops.model.application.AgentPlanningModelGateway;
import com.jundaodsj.insightops.model.application.ModelUsage;
import com.jundaodsj.insightops.project.application.AdminProjectStore;
import com.jundaodsj.insightops.tool.application.registry.AgentToolDefinition;
import com.jundaodsj.insightops.tool.application.registry.AgentToolRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
        when(dispatcher.execute(any(), anyString(), any(), any())).thenReturn(
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
        ArgumentCaptor<AgentPlanningModelGateway.AgentPlanRequest> plans =
                ArgumentCaptor.forClass(AgentPlanningModelGateway.AgentPlanRequest.class);
        verify(planning, times(2)).plan(plans.capture());
        assertThat(plans.getAllValues().get(1).exchanges()).hasSize(1);
        assertThat(plans.getAllValues().get(1).exchanges().getFirst().responseJson())
                .contains("official");
        verify(dispatcher).execute(any(), anyString(), any(), any());
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
        when(dispatcher.execute(any(), anyString(), any(), any())).thenAnswer(invocation -> {
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
        verify(planning, times(2)).plan(any());
        verify(dispatcher, times(2)).execute(any(), anyString(), any(), any());
    }

    @Test
    void shouldSequentializeMultipleToolCallsAcrossPlanningRounds() {
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
                .thenReturn(plan("", List.of(first, second), ModelUsage.unknown()))
                .thenReturn(plan("FINISH", List.of(), ModelUsage.unknown()));
        when(dispatcher.execute(any(), anyString(), any(), any())).thenAnswer(invocation -> {
            String toolName = invocation.getArgument(1);
            return new AgentToolDispatcher.ExecutionResult(
                    UUID.randomUUID(), toolName, Map.of("answer", toolName),
                    "evidence-" + toolName, List.of(), List.of(), 1, null);
        });
        AgentLoopService service = service(planning, dispatcher, audit, projects, 4);

        AgentLoopService.LoopResult result = service.run(request(), mockListener(), () -> true);

        assertThat(result.toolRounds()).isEqualTo(2);
        assertThat(result.limitReached()).isFalse();
        ArgumentCaptor<String> toolNames = ArgumentCaptor.forClass(String.class);
        verify(dispatcher, times(2)).execute(any(), toolNames.capture(), any(), any());
        assertThat(toolNames.getAllValues()).containsExactly("test_search", "second_search");
        ArgumentCaptor<AgentPlanningModelGateway.AgentPlanRequest> plans =
                ArgumentCaptor.forClass(AgentPlanningModelGateway.AgentPlanRequest.class);
        verify(planning, times(3)).plan(plans.capture());
        assertThat(plans.getAllValues().get(1).exchanges()).hasSize(1);
        assertThat(plans.getAllValues().get(2).exchanges()).hasSize(2);
    }

    private static AgentLoopService service(
            AgentPlanningModelGateway planning,
            AgentToolDispatcher dispatcher,
            AgentLoopAuditStore audit,
            AdminProjectStore projects,
            int maxRounds) {
        return new AgentLoopService(
                planning, dispatcher,
                new AgentToolRegistry(List.of(tool(), secondTool())), audit, projects,
                new DeepSeekModelProperties(
                        true, "https://api.deepseek.com", "deepseek-v4-flash", false,
                        0.2, 4096, maxRounds, 90, 2, false),
                new ObjectMapper());
    }

    private static AgentToolDefinition tool() {
        return new AgentToolDefinition(
                "test_search", 1, "Search test evidence", true,
                AgentToolDefinition.AccessLevel.WORKSPACE_MEMBER,
                AgentToolDefinition.RiskLevel.READ_ONLY,
                AgentToolDefinition.ApprovalPolicy.NOT_REQUIRED,
                Duration.ofSeconds(10), 10_000,
                List.of(AgentToolDefinition.Parameter.string(
                        "query", "Search query", true, 200)),
                List.of(AgentToolDefinition.Parameter.string(
                        "answer", "Search answer", false, 2_000)));
    }

    private static AgentToolDefinition secondTool() {
        return new AgentToolDefinition(
                "second_search", 1, "Search secondary evidence", true,
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
