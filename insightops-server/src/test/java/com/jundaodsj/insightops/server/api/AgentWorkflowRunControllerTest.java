package com.jundaodsj.insightops.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.agent.application.AgentWorkflowRunStore;
import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentWorkflowRunControllerTest {

    private static final UUID RUN_ID = UUID.fromString("10000000-0000-0000-0000-000000000024");
    private static final UUID WORKSPACE_ID = UUID.fromString("20000000-0000-0000-0000-000000000024");
    private static final UUID USER_ID = UUID.fromString("30000000-0000-0000-0000-000000000024");

    @Test
    void exposesDynamicJsonAsStandardCollectionsAtTheHttpBoundary() {
        AgentWorkflowRunStore store = mock(AgentWorkflowRunStore.class);
        when(store.find(RUN_ID)).thenReturn(Optional.of(run()));
        AgentWorkflowRunController controller = new AgentWorkflowRunController(store, new ObjectMapper());

        ApiResponse<AgentWorkflowRunController.WorkflowRunView> response =
                controller.detail(RUN_ID, request());

        assertThat(response.data().graphSpec()).isInstanceOf(Map.class);
        assertThat(response.data().graphSpec()).isEqualTo(Map.of(
                "inputs", Map.of("topic", Map.of("type", "string")),
                "nodes", List.of(Map.of("id", "research_1"))));
        assertThat(response.data().inputs()).isEqualTo(Map.of("topic", "Spring Boot 4"));
        assertThat(response.data().nodes().getFirst().dependencyNodeIds())
                .isEqualTo(List.of("research_0"));
        assertThat(response.data().nodes().getFirst().resolvedInput())
                .isEqualTo(Map.of("query", "Spring Boot 4 release notes"));
    }

    private static AgentWorkflowRunStore.WorkflowRun run() {
        Instant now = Instant.parse("2026-08-24T00:00:00Z");
        AgentWorkflowRunStore.WorkflowNode node = new AgentWorkflowRunStore.WorkflowNode(
                UUID.randomUUID(), "research_1", "knowledge_hybrid_search", 1, "READ_ONLY",
                true, "ALWAYS", "[\"research_0\"]", "{\"query\":\"${inputs.topic}\"}",
                "[\"sources\"]", "{\"query\":\"Spring Boot 4 release notes\"}",
                "{\"resultCount\":1}", "{\"sources\":[]}", null, "[]", "[]",
                "SUCCEEDED", 1, UUID.randomUUID(), UUID.randomUUID(), null,
                0, 0, BigDecimal.ZERO, null, now, now, now, now, List.of());
        return new AgentWorkflowRunStore.WorkflowRun(
                RUN_ID, WORKSPACE_ID, USER_ID, UUID.randomUUID(), UUID.randomUUID(),
                "Version comparison", 1, "Compare versions",
                "{\"inputs\":{\"topic\":{\"type\":\"string\"}},\"nodes\":[{\"id\":\"research_1\"}]}",
                "{\"topic\":\"Spring Boot 4\"}", "fingerprint", UUID.randomUUID(),
                null, null, null, now, List.of(node));
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE, "trace-workflow-run");
        request.setAttribute(CurrentAccount.ATTRIBUTE, new AccountWorkspaceStore.AccountRecord(
                USER_ID, "alpha-owner", "Alpha Owner", WORKSPACE_ID, "Alpha Workspace",
                "SYSTEM_ADMIN", "OWNER", "hash", false));
        return request;
    }
}
