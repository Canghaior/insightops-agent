package com.jundaodsj.insightops.server.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.server.tool.AgentToolRegistryConfiguration;
import com.jundaodsj.insightops.tool.application.registry.AgentToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentWorkflowExpressionServiceTest {

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final AgentWorkflowExpressionService service = new AgentWorkflowExpressionService(
            json, new AgentToolRegistry(AgentToolRegistryConfiguration.definitions(true)));

    @Test
    void resolvesTypedEntryInputsAndOnlyExposedDependencyOutputs() {
        AgentWorkflowExpressionService.Graph graph = service.validateGraph("""
                {"inputs":{"topic":{"type":"string","required":true,"maxLength":100}},
                 "nodes":[
                   {"id":"first","toolName":"knowledge_hybrid_search",
                    "arguments":{"query":"${inputs.topic}","candidateLimit":8},
                    "dependsOn":[],"exposeOutputs":["resultCount"]},
                   {"id":"second","toolName":"knowledge_hybrid_search",
                    "arguments":{"query":"${inputs.topic} count ${first.output.resultCount}",
                                 "candidateLimit":8},
                    "dependsOn":["first"]}
                 ]}
                """);

        Map<String, Object> inputs = service.validateInputs(graph, Map.of("topic", "Spring AI"));
        Map<String, Object> resolved = service.resolveArguments(
                graph.nodes().get(1), inputs, Map.of("first", Map.of("resultCount", 3)));

        assertThat(resolved).containsEntry("query", "Spring AI count 3")
                .containsEntry("candidateLimit", 8);
    }

    @Test
    void rejectsMissingInputsAndUnexposedOrImplicitDependencies() {
        AgentWorkflowExpressionService.Graph graph = service.validateGraph("""
                {"inputs":{"topic":{"type":"string","required":true}},
                 "nodes":[{"id":"first","toolName":"knowledge_hybrid_search",
                   "arguments":{"query":"${inputs.topic}","candidateLimit":8},
                   "dependsOn":[]}]}
                """);
        assertThatThrownBy(() -> service.validateInputs(graph, Map.of()))
                .hasMessageContaining("WORKFLOW_INPUT_MISSING");

        assertThatThrownBy(() -> service.validateGraph("""
                {"nodes":[
                  {"id":"first","toolName":"knowledge_hybrid_search",
                   "arguments":{"query":"one","candidateLimit":8},"dependsOn":[]},
                  {"id":"second","toolName":"knowledge_hybrid_search",
                   "arguments":{"query":"${first.output.resultCount}","candidateLimit":8},
                   "dependsOn":["first"]}]}
                """))
                .hasMessageContaining("WORKFLOW_OUTPUT_NOT_EXPOSED");
    }

    @Test
    void preservesArrayTypeForExactDependencyOutputExpressionDuringPreflight() {
        AgentWorkflowExpressionService.Graph graph = service.validateGraph("""
                {"nodes":[
                  {"id":"first","toolName":"project_intelligence_event_search",
                   "arguments":{"question":"official projects",
                                "eventTypes":["ISSUE"],"limit":8},
                   "dependsOn":[],"exposeOutputs":["sources"]},
                  {"id":"second","toolName":"github_release_list",
                   "arguments":{"projectIds":"${first.output.sources}",
                                "maxReleasesPerProject":5,"includePrereleases":false},
                   "dependsOn":["first"]}]}
                """);

        service.validateArgumentContracts(graph);
        Map<String, Object> resolved = service.resolveArguments(
                graph.nodes().get(1), Map.of(),
                Map.of("first", Map.of("sources", java.util.List.of("spring-ai"))));

        assertThat(resolved.get("projectIds"))
                .isEqualTo(java.util.List.of("spring-ai"));
    }
}
