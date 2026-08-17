package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import com.jundaodsj.insightops.knowledge.application.KnowledgeStore;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeAdminControllerTest {
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID WORKSPACE_ID = UUID.randomUUID();
    private static final UUID SOURCE_ID = UUID.randomUUID();

    @Test
    void systemAdministratorCanInspectAndRequestCollection() {
        KnowledgeStore store = mock(KnowledgeStore.class);
        when(store.sourceStatus(WORKSPACE_ID)).thenReturn(List.of());
        when(store.requestSync(org.mockito.ArgumentMatchers.eq(WORKSPACE_ID),
                org.mockito.ArgumentMatchers.eq(SOURCE_ID), org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(true);
        var controller = new KnowledgeAdminController(store);

        assertThat(controller.sources(request("SYSTEM_ADMIN")).data()).isEmpty();
        controller.sync(SOURCE_ID, request("SYSTEM_ADMIN"));

        verify(store).sourceStatus(WORKSPACE_ID);
    }

    @Test
    void ordinaryUserCannotAdministerKnowledgeSources() {
        var controller = new KnowledgeAdminController(mock(KnowledgeStore.class));
        assertThatThrownBy(() -> controller.sources(request("USER")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
    }

    private static MockHttpServletRequest request(String systemRole) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE, "trace-knowledge");
        request.setAttribute(CurrentAccount.ATTRIBUTE, new AccountWorkspaceStore.AccountRecord(
                USER_ID, "alpha-owner", "Alpha Owner", WORKSPACE_ID, "Alpha Workspace",
                systemRole, "OWNER", "hash", false));
        return request;
    }
}
