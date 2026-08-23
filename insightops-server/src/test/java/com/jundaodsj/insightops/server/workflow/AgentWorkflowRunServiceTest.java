package com.jundaodsj.insightops.server.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.agent.application.AgentRunQuery;
import com.jundaodsj.insightops.agent.application.AgentWorkflowRunStore;
import com.jundaodsj.insightops.agent.application.AgentWorkflowTemplateStore;
import com.jundaodsj.insightops.conversation.application.ChatRunStore;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.server.chat.DurableChatRunCoordinator;
import com.jundaodsj.insightops.server.tool.AgentToolRegistryConfiguration;
import com.jundaodsj.insightops.tool.application.registry.AgentToolDefinition;
import com.jundaodsj.insightops.tool.application.registry.AgentToolRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentWorkflowRunServiceTest {

    private static final UUID WORKSPACE = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID TEMPLATE = UUID.randomUUID();
    private static final UUID VERSION = UUID.randomUUID();
    private final AgentWorkflowTemplateStore templates = mock(AgentWorkflowTemplateStore.class);
    private final AgentWorkflowRunStore workflowRuns = mock(AgentWorkflowRunStore.class);
    private final ChatRunStore chatRuns = mock(ChatRunStore.class);
    private final AgentRunQuery runQuery = mock(AgentRunQuery.class);
    private final DurableChatRunCoordinator durableRuns = mock(DurableChatRunCoordinator.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final AgentToolRegistry registry = new AgentToolRegistry(
            AgentToolRegistryConfiguration.definitions(true));
    private final AgentWorkflowRunService service = new AgentWorkflowRunService(
            templates, workflowRuns, chatRuns, runQuery, durableRuns,
            new AgentWorkflowExpressionService(json, registry), registry, json);

    @Test
    void launchesActiveVersionAsImmutableRunSnapshotAndEnqueuesIt() {
        UUID sessionId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        when(workflowRuns.findByRequest(WORKSPACE, USER, requestId)).thenReturn(Optional.empty());
        when(templates.find(WORKSPACE, TEMPLATE)).thenReturn(Optional.of(template()));
        when(chatRuns.startRun(any(), any(), any(), any(), any(), any())).thenReturn(sessionId);

        AgentWorkflowRunService.LaunchResult result = service.launch(
                new ActorContext(USER, WORKSPACE), false,
                AgentToolDefinition.AccessLevel.WORKSPACE_MEMBER,
                TEMPLATE, VERSION, null, requestId, Map.of("topic", "Spring AI"), "trace-p24b");

        ArgumentCaptor<AgentWorkflowRunStore.WorkflowRunDraft> run =
                ArgumentCaptor.forClass(AgentWorkflowRunStore.WorkflowRunDraft.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentWorkflowRunStore.NodeDraft>> nodes = ArgumentCaptor.forClass(List.class);
        verify(workflowRuns).create(run.capture(), nodes.capture());
        assertThat(run.getValue().templateVersionId()).isEqualTo(VERSION);
        assertThat(run.getValue().templateVersion()).isEqualTo(2);
        assertThat(run.getValue().entryQuestion()).isEqualTo("研究 Spring AI");
        assertThat(run.getValue().inputJson()).contains("Spring AI");
        assertThat(run.getValue().graphSpecJson()).contains("${inputs.topic}");
        assertThat(run.getValue().toolContractFingerprint()).hasSize(64);
        assertThat(nodes.getValue()).singleElement().satisfies(node -> {
            assertThat(node.logicalNodeId()).isEqualTo("research");
            assertThat(node.argumentTemplateJson()).contains("${inputs.topic}");
            assertThat(node.status()).isEqualTo("PENDING");
        });
        verify(durableRuns).enqueue(any(), any(), org.mockito.ArgumentMatchers.eq(sessionId),
                org.mockito.ArgumentMatchers.eq("trace-p24b"), org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq(AgentToolDefinition.AccessLevel.WORKSPACE_MEMBER),
                org.mockito.ArgumentMatchers.eq("研究 Spring AI"),
                org.mockito.ArgumentMatchers.eq("研究 Spring AI"),
                org.mockito.ArgumentMatchers.isNull(), any());
        assertThat(result.sessionId()).isEqualTo(sessionId);
        assertThat(result.duplicate()).isFalse();
    }

    @Test
    void returnsExistingRequestWithoutCreatingAnotherRun() {
        UUID requestId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        when(workflowRuns.findByRequest(WORKSPACE, USER, requestId))
                .thenReturn(Optional.of(existing(runId, requestId)));

        AgentWorkflowRunService.LaunchResult result = service.launch(
                new ActorContext(USER, WORKSPACE), false,
                AgentToolDefinition.AccessLevel.WORKSPACE_MEMBER,
                TEMPLATE, VERSION, null, requestId, Map.of(), "trace-duplicate");

        assertThat(result.runId()).isEqualTo(runId);
        assertThat(result.duplicate()).isTrue();
        verify(workflowRuns, never()).create(any(), any());
        verify(durableRuns, never()).enqueue(any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), any(), any(), any());
    }

    private static AgentWorkflowTemplateStore.WorkflowTemplate template() {
        String graph = """
                {"inputs":{"topic":{"type":"string","required":true,"maxLength":200}},
                 "nodes":[{"id":"research","toolName":"knowledge_hybrid_search",
                  "arguments":{"query":"${inputs.topic}","candidateLimit":8},
                  "dependsOn":[],"condition":"ALWAYS","required":true,
                  "exposeOutputs":["resultCount","sources"]}]}
                """;
        AgentWorkflowTemplateStore.WorkflowVersion version =
                new AgentWorkflowTemplateStore.WorkflowVersion(
                        VERSION, TEMPLATE, 2, "ACTIVE", "P2.4-B", "研究 ${inputs.topic}",
                        graph, USER, Instant.now(), Instant.now());
        return new AgentWorkflowTemplateStore.WorkflowTemplate(
                TEMPLATE, WORKSPACE, "技术研究", "固定图", "TECH_RESEARCH",
                "ACTIVE", VERSION, USER, Instant.now(), Instant.now(), List.of(version));
    }

    private static AgentWorkflowRunStore.WorkflowRun existing(UUID runId, UUID requestId) {
        return new AgentWorkflowRunStore.WorkflowRun(
                runId, WORKSPACE, USER, TEMPLATE, VERSION, "技术研究", 2,
                "研究 Spring AI", "{}", "{}", "a".repeat(64), requestId,
                null, runId, null, Instant.now(), List.of());
    }
}
