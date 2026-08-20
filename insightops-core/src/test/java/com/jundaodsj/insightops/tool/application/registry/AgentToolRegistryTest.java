package com.jundaodsj.insightops.tool.application.registry;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentToolRegistryTest {

    @Test
    void shouldRejectDuplicatesAndUnknownTools() {
        AgentToolDefinition definition = definition("tool_alpha", true,
                AgentToolDefinition.AccessLevel.WORKSPACE_MEMBER);

        assertThatThrownBy(() -> new AgentToolRegistry(List.of(definition, definition)))
                .isInstanceOf(AgentToolRegistry.ToolRegistryException.class)
                .extracting(exception -> ((AgentToolRegistry.ToolRegistryException) exception).code())
                .isEqualTo(AgentToolRegistry.ErrorCode.DUPLICATE_TOOL);
        AgentToolRegistry registry = new AgentToolRegistry(List.of(definition));
        assertThatThrownBy(() -> registry.requireEnabled("missing_tool"))
                .isInstanceOf(AgentToolRegistry.ToolRegistryException.class)
                .hasMessageContaining("unknown tool");
    }

    @Test
    void shouldFilterDisabledAndPrivilegedTools() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(
                definition("member_tool", true,
                        AgentToolDefinition.AccessLevel.WORKSPACE_MEMBER),
                definition("owner_tool", true,
                        AgentToolDefinition.AccessLevel.WORKSPACE_OWNER),
                definition("disabled_tool", false,
                        AgentToolDefinition.AccessLevel.WORKSPACE_MEMBER)));

        assertThat(registry.availableTo(AgentToolDefinition.AccessLevel.WORKSPACE_MEMBER))
                .extracting(AgentToolDefinition::name)
                .containsExactly("member_tool");
        assertThat(registry.availableTo(AgentToolDefinition.AccessLevel.SYSTEM_ADMIN))
                .extracting(AgentToolDefinition::name)
                .containsExactly("member_tool", "owner_tool");
        assertThatThrownBy(() -> registry.requireEnabled("disabled_tool"))
                .hasMessageContaining("disabled");
    }

    @Test
    void shouldExposeProviderNeutralFunctionSchemas() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(
                definition("member_tool", true,
                        AgentToolDefinition.AccessLevel.WORKSPACE_MEMBER)));

        assertThat(registry.modelToolSchemas(AgentToolDefinition.AccessLevel.WORKSPACE_MEMBER))
                .singleElement()
                .satisfies(schema -> {
                    assertThat(schema).containsEntry("type", "function");
                    Map<?, ?> function = (Map<?, ?>) schema.get("function");
                    assertThat(function.get("name")).isEqualTo("member_tool");
                });
    }

    private static AgentToolDefinition definition(
            String name, boolean enabled, AgentToolDefinition.AccessLevel accessLevel) {
        return new AgentToolDefinition(
                name, 1, "Test tool", enabled, accessLevel,
                AgentToolDefinition.RiskLevel.READ_ONLY,
                AgentToolDefinition.ApprovalPolicy.NOT_REQUIRED,
                Duration.ofSeconds(5), 10_000,
                List.of(AgentToolDefinition.Parameter.string(
                        "query", "Question", true, 4_000)), List.of());
    }
}
