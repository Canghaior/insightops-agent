package com.jundaodsj.insightops.tool.application.registry;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentToolDefinitionTest {

    @Test
    void shouldBuildClosedJsonSchemaAndValidateInput() {
        AgentToolDefinition definition = definition();

        assertThat(definition.inputSchema())
                .containsEntry("type", "object")
                .containsEntry("additionalProperties", false);
        assertThat(definition.validateInput(Map.of(
                "query", "Spring AI latest release",
                "limit", 5,
                "projectIds", List.of("spring-ai"))))
                .containsEntry("limit", 5);
        assertThatThrownBy(() -> definition.validateInput(Map.of(
                "query", "Spring AI", "limit", 31,
                "projectIds", List.of("spring-ai"))))
                .hasMessageContaining("out of range");
        assertThatThrownBy(() -> definition.validateInput(Map.of(
                "query", "Spring AI", "limit", 5,
                "projectIds", List.of("spring-ai"), "unknown", true)))
                .hasMessageContaining("unknown fields");
    }

    @Test
    void shouldRejectMissingRequiredAndWrongTypes() {
        AgentToolDefinition definition = definition();

        assertThatThrownBy(() -> definition.validateInput(Map.of("limit", 5)))
                .hasMessageContaining("query");
        assertThatThrownBy(() -> definition.validateInput(Map.of(
                "query", "Spring AI", "limit", "five",
                "projectIds", List.of("spring-ai"))))
                .hasMessageContaining("INTEGER");
    }

    @Test
    void shouldRequireApprovalForMutatingTools() {
        assertThatThrownBy(() -> new AgentToolDefinition(
                "report_delete", 1, "Delete a report", true,
                AgentToolDefinition.AccessLevel.WORKSPACE_MEMBER,
                AgentToolDefinition.RiskLevel.MUTATING,
                AgentToolDefinition.ApprovalPolicy.NOT_REQUIRED,
                Duration.ofSeconds(5), 10_000, List.of(), List.of()))
                .hasMessageContaining("must require approval");
    }

    private static AgentToolDefinition definition() {
        return new AgentToolDefinition(
                "github_release_list", 1, "List official GitHub releases", true,
                AgentToolDefinition.AccessLevel.WORKSPACE_MEMBER,
                AgentToolDefinition.RiskLevel.READ_ONLY,
                AgentToolDefinition.ApprovalPolicy.NOT_REQUIRED,
                Duration.ofSeconds(20), 100_000,
                List.of(
                        AgentToolDefinition.Parameter.string(
                                "query", "Research question", true, 4_000),
                        AgentToolDefinition.Parameter.integer(
                                "limit", "Maximum releases", true, 1, 30),
                        AgentToolDefinition.Parameter.stringArray(
                                "projectIds", "Project identifiers", true, 3)),
                List.of(AgentToolDefinition.Parameter.json(
                        "releases", "Official release records", true)));
    }
}
