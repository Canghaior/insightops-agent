package com.jundaodsj.insightops.server.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.server.chat.AgentToolDispatcher;
import com.jundaodsj.insightops.tool.application.AgentToolApprovalStore;
import com.jundaodsj.insightops.tool.application.registry.AgentToolDefinition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentToolApprovalServiceTest {

    @Test
    void shouldPersistPendingApprovalWithoutExecutingSideEffect() {
        AgentToolApprovalStore store = mock(AgentToolApprovalStore.class);
        RegisteredToolExecutionService.Session session = mock(RegisteredToolExecutionService.Session.class);
        UUID runId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        UUID callId = UUID.randomUUID();
        when(session.runId()).thenReturn(runId);
        when(session.stepId()).thenReturn(stepId);
        when(session.toolCallId()).thenReturn(callId);
        when(session.toolName()).thenReturn("user_memory_upsert");
        when(store.request(any())).thenAnswer(invocation -> {
            AgentToolApprovalStore.Request request = invocation.getArgument(0);
            return new AgentToolApprovalStore.Approval(
                    request.id(), request.runId(), request.toolCallId(), request.toolName(),
                    request.summary(), "PENDING", request.requestPayload(), null, null,
                    null, request.expiresAt(), null, null, null,
                    request.createdAt(), request.createdAt());
        });
        AgentToolApprovalService service = new AgentToolApprovalService(store, new ObjectMapper());
        AgentToolDispatcher.ExecutionContext context = new AgentToolDispatcher.ExecutionContext(
                runId, UUID.randomUUID(), UUID.randomUUID(), false,
                AgentToolDefinition.AccessLevel.WORKSPACE_MEMBER, 1, 1, 1);

        var pending = service.requestMemoryUpsert(context, session, Map.of(
                "key", "回答风格", "value", "先给结论", "category", "preference"));

        assertThat(pending.observation()).containsEntry("status", "WAITING_APPROVAL");
        ArgumentCaptor<AgentToolApprovalStore.Request> request =
                ArgumentCaptor.forClass(AgentToolApprovalStore.Request.class);
        verify(store).request(request.capture());
        assertThat(request.getValue().requestPayload()).contains("PREFERENCE");
        verify(session).waitForApproval(pending.observation());
    }

    @Test
    void shouldRejectUnsupportedMemoryCategoryBeforeCreatingApproval() {
        AgentToolApprovalStore store = mock(AgentToolApprovalStore.class);
        RegisteredToolExecutionService.Session session = mock(RegisteredToolExecutionService.Session.class);
        when(session.toolName()).thenReturn("user_memory_upsert");
        AgentToolApprovalService service = new AgentToolApprovalService(store, new ObjectMapper());
        AgentToolDispatcher.ExecutionContext context = new AgentToolDispatcher.ExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), false,
                AgentToolDefinition.AccessLevel.WORKSPACE_MEMBER, 1, 1, 1);

        assertThatThrownBy(() -> service.requestMemoryUpsert(context, session, Map.of(
                "key", "x", "value", "y", "category", "SYSTEM")))
                .isInstanceOf(AgentToolApprovalStore.ApprovalException.class)
                .hasMessageContaining("category");
    }
}
