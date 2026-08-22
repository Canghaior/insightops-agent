package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.agent.application.AgentEvaluationStore;
import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import com.jundaodsj.insightops.server.evaluation.AgentEvaluationService;
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

class AgentEvaluationAdminControllerTest {

    @Test
    void workspaceOwnerCanReadGovernanceOverview() {
        AgentEvaluationService service = mock(AgentEvaluationService.class);
        UUID workspaceId = UUID.randomUUID();
        AgentEvaluationStore.Overview overview = new AgentEvaluationStore.Overview(
                List.of(), List.of(), List.of(), null);
        AgentEvaluationService.Defaults defaults = new AgentEvaluationService.Defaults(
                "deepseek-v4-flash", 0, 2048, "a".repeat(64));
        when(service.overview(workspaceId)).thenReturn(overview);
        when(service.defaults()).thenReturn(defaults);
        AgentEvaluationAdminController controller = new AgentEvaluationAdminController(service);

        ApiResponse<AgentEvaluationAdminController.OverviewResponse> response =
                controller.overview(request(workspaceId, "USER", "OWNER"));

        assertThat(response.traceId()).isEqualTo("trace-evaluation");
        assertThat(response.data().governance()).isSameAs(overview);
        assertThat(response.data().defaults()).isSameAs(defaults);
    }

    @Test
    void systemAdministratorCanReadGovernanceOverview() {
        AgentEvaluationService service = mock(AgentEvaluationService.class);
        UUID workspaceId = UUID.randomUUID();
        when(service.overview(workspaceId)).thenReturn(new AgentEvaluationStore.Overview(
                List.of(), List.of(), List.of(), null));
        AgentEvaluationAdminController controller = new AgentEvaluationAdminController(service);

        ApiResponse<AgentEvaluationAdminController.OverviewResponse> response =
                controller.overview(request(workspaceId, "SYSTEM_ADMIN", "MEMBER"));

        assertThat(response.traceId()).isEqualTo("trace-evaluation");
    }

    @Test
    void workspaceMemberIsForbiddenFromEvaluationGovernance() {
        AgentEvaluationAdminController controller = new AgentEvaluationAdminController(
                mock(AgentEvaluationService.class));

        assertThatThrownBy(() -> controller.overview(
                request(UUID.randomUUID(), "USER", "MEMBER")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.FORBIDDEN));
    }

    private static MockHttpServletRequest request(
            UUID workspaceId, String systemRole, String workspaceRole) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE, "trace-evaluation");
        request.setAttribute(CurrentAccount.ATTRIBUTE, new AccountWorkspaceStore.AccountRecord(
                UUID.randomUUID(), "operator", "Operator", workspaceId, "Workspace",
                systemRole, workspaceRole, "hash", false));
        return request;
    }
}
