package com.jundaodsj.insightops.server.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.agent.application.AgentWorkflowTemplateStore;
import com.jundaodsj.insightops.server.tool.AgentToolRegistryConfiguration;
import com.jundaodsj.insightops.tool.application.registry.AgentToolRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AgentWorkflowServiceTest {

    private final AgentWorkflowService service = new AgentWorkflowService(
            mock(AgentWorkflowTemplateStore.class),
            new AgentToolRegistry(AgentToolRegistryConfiguration.definitions(true)),
            new ObjectMapper().findAndRegisterModules());

    @Test
    void previewsValidatedGraphInDependencyWaves() {
        AgentWorkflowService.Preview preview = service.preview("""
                {"reason":"compare", "nodes":[
                  {"id":"spring","toolName":"knowledge_hybrid_search",
                   "arguments":{"query":"Spring AI","candidateLimit":8},
                   "dependsOn":[],"condition":"ALWAYS","required":true},
                  {"id":"langchain","toolName":"knowledge_hybrid_search",
                   "arguments":{"query":"LangChain4j","candidateLimit":8},
                   "dependsOn":[],"condition":"ALWAYS","required":true},
                  {"id":"evidence","toolName":"knowledge_hybrid_search",
                   "arguments":{"query":"compare official evidence","candidateLimit":12},
                   "dependsOn":["spring","langchain"],
                   "condition":"ALL_SUCCESS","required":true}
                ]}
                """);

        assertThat(preview.nodeCount()).isEqualTo(3);
        assertThat(preview.maxParallelism()).isEqualTo(2);
        assertThat(preview.waves()).extracting("nodeIds")
                .containsExactly(java.util.List.of("spring", "langchain"),
                        java.util.List.of("evidence"));
        assertThat(preview.mutatingNodeCount()).isZero();
    }

    @Test
    void rejectsCyclesAndInvalidToolArgumentsBeforePersistence() {
        assertThatThrownBy(() -> service.preview("""
                {"nodes":[
                  {"id":"one","toolName":"knowledge_hybrid_search",
                   "arguments":{"query":"one","candidateLimit":8},"dependsOn":["two"]},
                  {"id":"two","toolName":"knowledge_hybrid_search",
                   "arguments":{"query":"two","candidateLimit":8},"dependsOn":["one"]}
                ]}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PLAN_DEPENDENCY_CYCLE");

        assertThatThrownBy(() -> service.preview("""
                {"nodes":[{"id":"one","toolName":"knowledge_hybrid_search",
                  "arguments":{"query":"one","candidateLimit":99},"dependsOn":[]}]}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WORKFLOW_TOOL_ARGUMENTS_INVALID");
    }

    @Test
    void keepsMutatingToolsBehindRuntimeApprovalBoundary() {
        AgentWorkflowService.Preview preview = service.preview("""
                {"nodes":[{"id":"memory","toolName":"user_memory_upsert",
                  "arguments":{"key":"style","value":"concise","category":"PREFERENCE"},
                  "dependsOn":[],"condition":"ALWAYS","required":true}]}
                """);

        assertThat(preview.mutatingNodeCount()).isEqualTo(1);
        assertThat(preview.warnings()).singleElement()
                .asString().contains("人工审批");
    }
}
