package com.jundaodsj.insightops.server.tool;

import com.jundaodsj.insightops.tool.application.registry.AgentToolDefinition;
import com.jundaodsj.insightops.tool.application.registry.AgentToolNames;
import com.jundaodsj.insightops.tool.application.registry.AgentToolRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolRegistryConfigurationTest {

    @Test
    void shouldRegisterThreeReadOnlyBuiltInTools() {
        AgentToolRegistry registry = new AgentToolRegistry(
                AgentToolRegistryConfiguration.definitions(true));

        assertThat(registry.definitions())
                .extracting(definition -> definition.name())
                .containsExactly(
                        AgentToolNames.GITHUB_RELEASE_LIST,
                        AgentToolNames.KNOWLEDGE_HYBRID_SEARCH,
                        AgentToolNames.PROJECT_INTELLIGENCE_EVENT_SEARCH);
        assertThat(registry.definitions())
                .allMatch(definition -> definition.riskLevel()
                        == AgentToolDefinition.RiskLevel.READ_ONLY)
                .allMatch(definition -> definition.approvalPolicy()
                        == AgentToolDefinition.ApprovalPolicy.NOT_REQUIRED);
    }

    @Test
    void shouldHideRagToolFromAvailableSchemasWhenRagIsDisabled() {
        AgentToolRegistry registry = new AgentToolRegistry(
                AgentToolRegistryConfiguration.definitions(false));

        assertThat(registry.availableTo(AgentToolDefinition.AccessLevel.SYSTEM_ADMIN))
                .extracting(definition -> definition.name())
                .doesNotContain(AgentToolNames.KNOWLEDGE_HYBRID_SEARCH)
                .hasSize(2);
    }
}
