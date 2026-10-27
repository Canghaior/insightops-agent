package com.jundaodsj.insightops.server.tool;

import com.jundaodsj.insightops.tool.application.registry.AgentToolDefinition;
import com.jundaodsj.insightops.tool.application.registry.AgentToolNames;
import com.jundaodsj.insightops.tool.application.registry.AgentToolRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolRegistryConfigurationTest {

    @Test
    void shouldRegisterReadOnlyAndApprovalGatedBuiltInTools() {
        AgentToolRegistry registry = new AgentToolRegistry(
                AgentToolRegistryConfiguration.definitions(true));

        assertThat(registry.definitions())
                .extracting(definition -> definition.name())
                .containsExactly(
                        AgentToolNames.GITHUB_ISSUE_READ,
                        AgentToolNames.GITHUB_RELEASE_LIST,
                        AgentToolNames.KNOWLEDGE_HYBRID_SEARCH,
                        AgentToolNames.MCP_READ_CALL,
                        AgentToolNames.PROJECT_INTELLIGENCE_EVENT_SEARCH,
                        AgentToolNames.USER_MEMORY_UPSERT);
        AgentToolDefinition memory = registry.find(AgentToolNames.USER_MEMORY_UPSERT)
                .orElseThrow();
        assertThat(memory.riskLevel()).isEqualTo(AgentToolDefinition.RiskLevel.MUTATING);
        assertThat(memory.approvalPolicy()).isEqualTo(
                AgentToolDefinition.ApprovalPolicy.REQUIRED);
        assertThat(registry.definitions().stream()
                .filter(definition -> !definition.name().equals(AgentToolNames.USER_MEMORY_UPSERT)))
                .allMatch(definition -> definition.riskLevel()
                        == AgentToolDefinition.RiskLevel.READ_ONLY);
    }

    @Test
    void shouldHideRagToolFromAvailableSchemasWhenRagIsDisabled() {
        AgentToolRegistry registry = new AgentToolRegistry(
                AgentToolRegistryConfiguration.definitions(false));

        assertThat(registry.availableTo(AgentToolDefinition.AccessLevel.SYSTEM_ADMIN))
                .extracting(definition -> definition.name())
                .doesNotContain(AgentToolNames.KNOWLEDGE_HYBRID_SEARCH)
                .hasSize(4);
    }

    @Test
    void shouldOnlyExposeIssueToolWhenMcpIsConfiguredAndRejectUnexpectedInputs() {
        AgentToolRegistry disabled = new AgentToolRegistry(
                AgentToolRegistryConfiguration.definitions(true));
        assertThat(disabled.availableTo(AgentToolDefinition.AccessLevel.WORKSPACE_MEMBER))
                .extracting(AgentToolDefinition::name)
                .doesNotContain(AgentToolNames.GITHUB_ISSUE_READ);
        AgentToolRegistry enabled = new AgentToolRegistry(
                AgentToolRegistryConfiguration.definitions(true, true));
        assertThat(enabled.availableTo(AgentToolDefinition.AccessLevel.WORKSPACE_MEMBER))
                .extracting(AgentToolDefinition::name)
                .contains(AgentToolNames.GITHUB_ISSUE_READ, AgentToolNames.GITHUB_RELEASE_LIST);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> enabled.validateInput(
                AgentToolNames.GITHUB_ISSUE_READ, AgentToolDefinition.AccessLevel.WORKSPACE_MEMBER,
                java.util.Map.of("projectId", "spring-projects/spring-ai", "issueNumber", 0)))
                .isInstanceOf(AgentToolRegistry.ToolRegistryException.class);
    }
}
