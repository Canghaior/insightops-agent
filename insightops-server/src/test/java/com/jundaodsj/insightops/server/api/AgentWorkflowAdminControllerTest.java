package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import com.jundaodsj.insightops.server.workflow.AgentWorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentWorkflowAdminControllerTest {

    @Test
    void workspaceOwnerCanReadWorkflowGovernance() {
        AgentWorkflowService service = mock(AgentWorkflowService.class);
        UUID workspaceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AgentWorkflowService.Overview overview = new AgentWorkflowService.Overview(
                List.of(), List.of(), 32);
        when(service.overview(workspaceId, userId)).thenReturn(overview);
        AgentWorkflowAdminController controller = new AgentWorkflowAdminController(service);

        ApiResponse<AgentWorkflowService.Overview> response = controller.overview(
                request(userId, workspaceId, "USER", "OWNER"));

        assertThat(response.traceId()).isEqualTo("trace-workflow");
        assertThat(response.data()).isSameAs(overview);
    }

    @Test
    void ordinaryMemberCannotManageWorkflowTemplates() {
        AgentWorkflowAdminController controller = new AgentWorkflowAdminController(
                mock(AgentWorkflowService.class));

        assertThatThrownBy(() -> controller.overview(
                request(UUID.randomUUID(), UUID.randomUUID(), "USER", "MEMBER")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.FORBIDDEN));
    }

    private static MockHttpServletRequest request(
            UUID userId, UUID workspaceId, String systemRole, String workspaceRole) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE, "trace-workflow");
        request.setAttribute(CurrentAccount.ATTRIBUTE, new AccountWorkspaceStore.AccountRecord(
                userId, "operator", "Operator", workspaceId, "Workspace",
                systemRole, workspaceRole, "hash", false));
        return request;
    }
}
