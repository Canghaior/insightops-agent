package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.agent.application.AgentRunQuery;
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

class AgentRunControllerTest {

    private static final UUID RUN_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final ActorContext ACTOR = new ActorContext(
            UUID.fromString("00000000-0000-0000-0000-000000000101"),
            UUID.fromString("00000000-0000-0000-0000-000000000001"));

    @Test
    void shouldListRunsWithNormalizedStatusAndTraceId() {
        RecordingQuery query = new RecordingQuery();
        AgentRunController controller = new AgentRunController(query);

        ApiResponse<AgentRunQuery.RunPage> response = controller.list(
                1, 10, " succeeded ", request());

        assertThat(response.traceId()).isEqualTo("trace-runs");
        assertThat(response.data().items()).hasSize(1);
        assertThat(query.status).isEqualTo("SUCCEEDED");
        assertThat(query.page).isEqualTo(1);
        assertThat(query.size).isEqualTo(10);
    }

    @Test
    void shouldReturnRunDetail() {
        AgentRunController controller = new AgentRunController(new RecordingQuery());

        ApiResponse<AgentRunQuery.RunDetail> response = controller.detail(RUN_ID, request());

        assertThat(response.data().id()).isEqualTo(RUN_ID);
        assertThat(response.data().sources()).containsExactly("https://github.com/example/releases/tag/v1");
        assertThat(response.data().steps()).hasSize(1);
        assertThat(response.data().toolCalls()).hasSize(1);
    }

    @Test
    void shouldRejectUnsupportedStatusAndMissingRun() {
        RecordingQuery query = new RecordingQuery();
        AgentRunController controller = new AgentRunController(query);

        assertThatThrownBy(() -> controller.list(0, 20, "UNKNOWN", request()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");

        query.missing = true;
        assertThatThrownBy(() -> controller.detail(RUN_ID, request()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE, "trace-runs");
        request.setAttribute(CurrentAccount.ATTRIBUTE, account());
        return request;
    }

    private static AccountWorkspaceStore.AccountRecord account() {
        return new AccountWorkspaceStore.AccountRecord(
                ACTOR.userId(), "alpha-owner", "Alpha Owner", ACTOR.workspaceId(),
                "Alpha Workspace", "SYSTEM_ADMIN", "OWNER", "hash", false);
    }

    private static final class RecordingQuery implements AgentRunQuery {
        private int page;
        private int size;
        private String status;
        private boolean missing;

        @Override
        public RunPage listRuns(ActorContext actor, int page, int size, String status) {
            assertThat(actor).isEqualTo(ACTOR);
            this.page = page;
            this.size = size;
            this.status = status;
            return new RunPage(List.of(summary()), 1, page, size, 1);
        }

        @Override
        public Optional<RunDetail> findRun(ActorContext actor, UUID runId) {
            assertThat(actor).isEqualTo(ACTOR);
            return missing ? Optional.empty() : Optional.of(detail());
        }

        private static RunSummary summary() {
            return new RunSummary(
                    RUN_ID, UUID.randomUUID(), "trace-runs", "SUCCEEDED", "question",
                    "deepseek", "deepseek-v4-flash", 1, 100, 20, 800L,
                    Instant.parse("2026-08-16T00:00:00Z"),
                    Instant.parse("2026-08-16T00:00:00.800Z"));
        }

        private static RunDetail detail() {
            UUID stepId = UUID.fromString("20000000-0000-0000-0000-000000000001");
            return new RunDetail(
                    RUN_ID, UUID.randomUUID(), "trace-runs", "SUCCEEDED", "question", "answer",
                    "deepseek", "deepseek-v4-flash", 1, 100, 20, null,
                    null, null, null, 800L, Instant.parse("2026-08-16T00:00:00Z"),
                    Instant.parse("2026-08-16T00:00:00.800Z"),
                    Instant.parse("2026-08-16T00:00:00Z"),
                    List.of("https://github.com/example/releases/tag/v1"),
                    List.of(),
                    List.of(new RunStep(
                            stepId, 1, "TOOL", "SUCCEEDED", java.util.Map.of("project", "spring-ai"),
                            java.util.Map.of("count", 1), 300L,
                            Instant.parse("2026-08-16T00:00:00Z"),
                            Instant.parse("2026-08-16T00:00:00.300Z"))),
                    List.of(new RunToolCall(
                            UUID.randomUUID(), stepId, "github_release_list", "SUCCEEDED",
                            java.util.Map.of("project", "spring-ai"), java.util.Map.of("count", 1),
                            null, 300L, Instant.parse("2026-08-16T00:00:00Z"),
                            Instant.parse("2026-08-16T00:00:00.300Z"), List.of())));
        }
    }
}
