package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.conversation.application.ChatRunStore;
import com.jundaodsj.insightops.conversation.application.ConversationManager;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatSessionControllerTest {

    private static final UUID SESSION_ID =
            UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final ActorContext ACTOR = new ActorContext(
            UUID.fromString("00000000-0000-0000-0000-000000000101"),
            UUID.fromString("00000000-0000-0000-0000-000000000001"));

    @Test
    void shouldReturnChronologicalSessionMessages() {
        ChatRunStore store = mock(ChatRunStore.class);
        ChatRunStore.SessionHistory history = new ChatRunStore.SessionHistory(
                SESSION_ID,
                "Spring AI 最新版本",
                List.of(
                        message("USER", "Spring AI 最新版本是什么？", 1),
                        message("ASSISTANT", "最新版本是 v2.0.0。", 2)),
                false);
        when(store.sessionHistory(ACTOR, SESSION_ID, 100)).thenReturn(Optional.of(history));
        ChatSessionController controller = new ChatSessionController(store, mock(ConversationManager.class));

        ApiResponse<ChatRunStore.SessionHistory> response = controller.messages(
                SESSION_ID,
                100,
                request());

        assertThat(response.traceId()).isEqualTo("trace-session");
        assertThat(response.data().messages())
                .extracting(ChatRunStore.HistoryMessage::role)
                .containsExactly("USER", "ASSISTANT");
    }

    @Test
    void shouldReturnNotFoundForUnknownSession() {
        ChatRunStore store = mock(ChatRunStore.class);
        when(store.sessionHistory(ACTOR, SESSION_ID, 100)).thenReturn(Optional.empty());
        ChatSessionController controller = new ChatSessionController(store, mock(ConversationManager.class));

        assertThatThrownBy(() -> controller.messages(SESSION_ID, 100, request()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
    }

    private static ChatRunStore.HistoryMessage message(String role, String content, int sequenceNo) {
        return new ChatRunStore.HistoryMessage(
                UUID.randomUUID(),
                role,
                content,
                sequenceNo,
                Instant.parse("2026-08-16T00:00:00Z").plusSeconds(sequenceNo));
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE, "trace-session");
        request.setAttribute(CurrentAccount.ATTRIBUTE, new AccountWorkspaceStore.AccountRecord(
                ACTOR.userId(), "alpha-owner", "Alpha Owner", ACTOR.workspaceId(),
                "Alpha Workspace", "OWNER", "hash", false));
        return request;
    }
}
