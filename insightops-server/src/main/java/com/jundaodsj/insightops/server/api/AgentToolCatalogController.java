package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.server.auth.CurrentAccount;
import com.jundaodsj.insightops.tool.application.registry.AgentToolDefinition;
import com.jundaodsj.insightops.tool.application.registry.AgentToolRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/agent/tools")
public class AgentToolCatalogController {

    private final AgentToolRegistry registry;

    public AgentToolCatalogController(AgentToolRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public ApiResponse<List<ToolDefinitionView>> list(HttpServletRequest request) {
        var account = CurrentAccount.account(request);
        AgentToolDefinition.AccessLevel access = access(account.systemRole(), account.role());
        List<ToolDefinitionView> tools = registry.availableTo(access).stream()
                .map(ToolDefinitionView::from)
                .toList();
        return new ApiResponse<>(
                (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE), tools);
    }

    static AgentToolDefinition.AccessLevel access(String systemRole, String workspaceRole) {
        if ("SYSTEM_ADMIN".equals(systemRole)) {
            return AgentToolDefinition.AccessLevel.SYSTEM_ADMIN;
        }
        if ("OWNER".equals(workspaceRole)) {
            return AgentToolDefinition.AccessLevel.WORKSPACE_OWNER;
        }
        return AgentToolDefinition.AccessLevel.WORKSPACE_MEMBER;
    }

    public record ToolDefinitionView(
            String name,
            int version,
            String description,
            String accessLevel,
            String riskLevel,
            String approvalPolicy,
            long timeoutMs,
            int maxResultCharacters,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema) {

        static ToolDefinitionView from(AgentToolDefinition definition) {
            return new ToolDefinitionView(
                    definition.name(),
                    definition.version(),
                    definition.description(),
                    definition.accessLevel().name(),
                    definition.riskLevel().name(),
                    definition.approvalPolicy().name(),
                    definition.timeout().toMillis(),
                    definition.maxResultCharacters(),
                    definition.inputSchema(),
                    definition.outputSchema());
        }
    }
}
