package com.jundaodsj.insightops.server.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.model.application.AgentPlanningModelGateway.PlannedToolCall;
import com.jundaodsj.insightops.tool.application.registry.AgentToolDefinition;
import com.jundaodsj.insightops.tool.application.registry.AgentToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConditionalTaskGraphTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentToolRegistry registry = new AgentToolRegistry(List.of(
            tool("primary", AgentToolDefinition.RiskLevel.READ_ONLY),
            tool("fallback", AgentToolDefinition.RiskLevel.READ_ONLY),
            tool("write", AgentToolDefinition.RiskLevel.MUTATING)));

    @Test
    void parsesFailureBranchAndEvaluatesCondition() {
        PlannedToolCall call = new PlannedToolCall("graph-1", ConditionalTaskGraph.FUNCTION_NAME, """
                {"reason":"fallback","nodes":[
                  {"id":"a","toolName":"primary","arguments":{}},
                  {"id":"b","toolName":"fallback","arguments":{},"dependsOn":["a"],
                   "condition":"ANY_FAILED","required":false}
                ]}
                """);
        ConditionalTaskGraph.Submission graph = ConditionalTaskGraph.parse(call, mapper, registry,
                AgentToolDefinition.AccessLevel.WORKSPACE_MEMBER, 8);
        assertThat(ConditionalTaskGraph.waves(graph.nodes())).hasSize(2);
        var fallback = graph.nodes().get(1);
        assertThat(ConditionalTaskGraph.conditionMatches(fallback, Map.of(
                graph.nodes().getFirst().id(), new ConditionalTaskGraph.NodeResult("FAILED", "TIMEOUT"))))
                .isTrue();
    }

    @Test
    void rejectsMutatingNodeThatSharesWave() {
        PlannedToolCall call = new PlannedToolCall("graph-2", ConditionalTaskGraph.FUNCTION_NAME, """
                {"nodes":[
                  {"id":"a","toolName":"primary","arguments":{}},
                  {"id":"b","toolName":"write","arguments":{}}
                ]}
                """);
        assertThatThrownBy(() -> ConditionalTaskGraph.parse(call, mapper, registry,
                AgentToolDefinition.AccessLevel.WORKSPACE_MEMBER, 8))
                .isInstanceOf(ConditionalTaskGraph.GraphException.class)
                .hasMessage("GRAPH_MUTATING_NODE_NOT_EXCLUSIVE");
    }

    private static AgentToolDefinition tool(String name, AgentToolDefinition.RiskLevel risk) {
        return new AgentToolDefinition(
                name, 1, name, true, AgentToolDefinition.AccessLevel.WORKSPACE_MEMBER,
                risk, risk == AgentToolDefinition.RiskLevel.MUTATING
                        ? AgentToolDefinition.ApprovalPolicy.REQUIRED
                        : AgentToolDefinition.ApprovalPolicy.NOT_REQUIRED,
                java.time.Duration.ofSeconds(5), 1_000, List.of(), List.of());
    }
}
