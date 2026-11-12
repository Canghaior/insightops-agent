package com.jundaodsj.insightops.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.agent.application.AgentRunQuery;
import com.jundaodsj.insightops.agent.application.AgentWorkflowRunStore;
import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import com.jundaodsj.insightops.server.report.ResearchEvidenceQuality;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RunEvidenceQualityControllerTest {
    private final AgentRunQuery runs = mock(AgentRunQuery.class);
    private final AgentWorkflowRunStore workflows = mock(AgentWorkflowRunStore.class);
    private final ActorContext actor = new ActorContext(UUID.randomUUID(), UUID.randomUUID());
    private final RunEvidenceQualityController controller = new RunEvidenceQualityController(runs, workflows,
            new ResearchEvidenceQuality(new ObjectMapper()));

    @Test void missingOrUnownedRunCannotExposeEvidenceOrWorkflowInputs() {
        UUID runId = UUID.randomUUID();
        assertThatThrownBy(() -> controller.detail(runId, request())).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
        verify(runs).findRun(actor, runId);
        verifyNoInteractions(workflows);
    }

    @Test void responseKeepsExecutionAndEvidenceStatusIndependent() {
        UUID runId = UUID.randomUUID();
        var run = mock(AgentRunQuery.RunDetail.class);
        when(run.status()).thenReturn("SUCCEEDED");
        when(run.answer()).thenReturn("工具失败后仍生成的答案");
        when(runs.findRun(actor, runId)).thenReturn(Optional.of(run));
        var response = controller.detail(runId, request());
        assertThat(response.data().status()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(run.status()).isEqualTo("SUCCEEDED");
        assertThat(response.data().compatibilityVerified()).isFalse();
    }

    @Test void batchChecksEveryRunsOwnershipAndRejectsOversizedRequests() {
        UUID runId = UUID.randomUUID();
        assertThatThrownBy(() -> controller.batch(Collections.nCopies(51, runId), request()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400 BAD_REQUEST");
        verifyNoInteractions(runs);
        assertThatThrownBy(() -> controller.batch(List.of(runId), request()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404 NOT_FOUND");
        verify(runs).findRun(actor, runId);
    }

    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest();
        request.setAttribute(CurrentAccount.ATTRIBUTE, new AccountWorkspaceStore.AccountRecord(actor.userId(),
                "member", "Member", actor.workspaceId(), "Workspace", "USER", "MEMBER", "hash", false));
        return request;
    }
}
