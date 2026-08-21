package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import com.jundaodsj.insightops.server.tool.AgentToolRegistryConfiguration;
import com.jundaodsj.insightops.tool.application.registry.AgentToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolCatalogControllerTest {

    @Test
    void shouldExposeEnabledSchemasWithoutImplementationDetails() {
        AgentToolCatalogController controller = new AgentToolCatalogController(
                new AgentToolRegistry(AgentToolRegistryConfiguration.definitions(true)));
        MockHttpServletRequest request = request("MEMBER");

        ApiResponse<java.util.List<AgentToolCatalogController.ToolDefinitionView>> response =
                controller.list(request);

        assertThat(response.traceId()).isEqualTo("trace-tools");
        assertThat(response.data()).hasSize(5)
                .allMatch(tool -> tool.inputSchema().containsKey("additionalProperties"));
        assertThat(response.data().stream()
                .filter(tool -> "user_memory_upsert".equals(tool.name())))
                .singleElement()
                .satisfies(tool -> {
                    assertThat(tool.riskLevel()).isEqualTo("MUTATING");
                    assertThat(tool.approvalPolicy()).isEqualTo("REQUIRED");
                });
    }

    @Test
    void shouldMapWorkspaceAndSystemRolesToRegistryAccess() {
        assertThat(AgentToolCatalogController.access("USER", "MEMBER").name())
                .isEqualTo("WORKSPACE_MEMBER");
        assertThat(AgentToolCatalogController.access("USER", "OWNER").name())
                .isEqualTo("WORKSPACE_OWNER");
        assertThat(AgentToolCatalogController.access("SYSTEM_ADMIN", "MEMBER").name())
                .isEqualTo("SYSTEM_ADMIN");
    }

    private static MockHttpServletRequest request(String workspaceRole) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE, "trace-tools");
        request.setAttribute(CurrentAccount.ATTRIBUTE, new AccountWorkspaceStore.AccountRecord(
                UUID.randomUUID(), "member", "Member", UUID.randomUUID(), "Workspace",
                "USER", workspaceRole, "hash", false));
        return request;
    }
}
