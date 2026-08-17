package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import com.jundaodsj.insightops.intelligence.application.IntelligenceStore;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntelligenceAdminControllerTest {
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID WORKSPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");

    @Test
    void shouldAllowSystemAdministratorToInspectAndRequestAnalysis() {
        IntelligenceStore store = mock(IntelligenceStore.class);
        when(store.analysisMetrics(eq(WORKSPACE_ID), any()))
                .thenReturn(new IntelligenceStore.AnalysisMetrics(2, new BigDecimal("0.010000"), 1, 0));
        when(store.adminStatuses(WORKSPACE_ID, 100)).thenReturn(List.of());
        when(store.requestAnalysis(eq(WORKSPACE_ID), eq(EVENT_ID), eq(USER_ID), any())).thenReturn(true);
        IntelligenceAdminController controller = new IntelligenceAdminController(store);

        var response = controller.overview(100, request("SYSTEM_ADMIN"));
        controller.analyze(EVENT_ID, request("SYSTEM_ADMIN"));

        assertThat(response.data().metrics().todayCalls()).isEqualTo(2);
        verify(store).requestAnalysis(eq(WORKSPACE_ID), eq(EVENT_ID), eq(USER_ID), any());
    }

    @Test
    void shouldRejectOrdinaryUserFromIntelligenceAdministration() {
        IntelligenceAdminController controller = new IntelligenceAdminController(mock(IntelligenceStore.class));

        assertThatThrownBy(() -> controller.overview(100, request("USER")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
        assertThatThrownBy(() -> controller.analyze(EVENT_ID, request("USER")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
    }

    private static MockHttpServletRequest request(String systemRole) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE, "trace-intelligence");
        request.setAttribute(CurrentAccount.ATTRIBUTE, new AccountWorkspaceStore.AccountRecord(
                USER_ID, "alpha-owner", "Alpha Owner", WORKSPACE_ID, "Alpha Workspace",
                systemRole, "OWNER", "hash", false));
        return request;
    }
}
