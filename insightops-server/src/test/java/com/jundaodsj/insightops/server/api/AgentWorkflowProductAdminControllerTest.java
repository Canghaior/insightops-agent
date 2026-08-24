package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import com.jundaodsj.insightops.server.workflow.AgentWorkflowProductService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentWorkflowProductAdminControllerTest {

    @Test
    void ownerCanReadTemplateQualityWithoutCrossingWorkspace() {
        AgentWorkflowProductService service = mock(AgentWorkflowProductService.class);
        UUID workspaceId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        AgentWorkflowProductService.QualityMetric empty =
                new AgentWorkflowProductService.QualityMetric(
                        "all", 0, 0, 0, 0, 0, 0,
                        0, BigDecimal.ZERO, 0, 0, 0, 0, 0, 0);
        AgentWorkflowProductService.TemplateAnalytics analytics =
                new AgentWorkflowProductService.TemplateAnalytics(
                        30, empty, List.of(), List.of(), List.of());
        when(service.analytics(workspaceId, templateId, 30)).thenReturn(analytics);
        AgentWorkflowProductAdminController controller =
                new AgentWorkflowProductAdminController(service);

        ApiResponse<AgentWorkflowProductService.TemplateAnalytics> response = controller.analytics(
                templateId, 30, request(UUID.randomUUID(), workspaceId, "USER", "OWNER"));

        assertThat(response.data()).isSameAs(analytics);
        assertThat(response.traceId()).isEqualTo("trace-p24c");
    }

    @Test
    void memberCannotExportShareImportOrReadQuality() {
        AgentWorkflowProductAdminController controller =
                new AgentWorkflowProductAdminController(mock(AgentWorkflowProductService.class));

        assertThatThrownBy(() -> controller.analytics(
                UUID.randomUUID(), 30,
                request(UUID.randomUUID(), UUID.randomUUID(), "USER", "MEMBER")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.FORBIDDEN));
    }

    private static MockHttpServletRequest request(
            UUID userId, UUID workspaceId, String systemRole, String workspaceRole) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE, "trace-p24c");
        request.setAttribute(CurrentAccount.ATTRIBUTE, new AccountWorkspaceStore.AccountRecord(
                userId, "operator", "Operator", workspaceId, "Workspace",
                systemRole, workspaceRole, "hash", false));
        return request;
    }
}
