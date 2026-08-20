package com.jundaodsj.insightops.server.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.tool.application.AgentToolExecutionStore;
import com.jundaodsj.insightops.tool.application.registry.AgentToolDefinition;
import com.jundaodsj.insightops.tool.application.registry.AgentToolNames;
import com.jundaodsj.insightops.tool.application.registry.AgentToolRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegisteredToolExecutionServiceTest {

    @Test
    void shouldValidateStartAndCompleteOneAuditedExecution() {
        RecordingStore store = new RecordingStore();
        RegisteredToolExecutionService service = service(store);
        UUID runId = UUID.randomUUID();

        RegisteredToolExecutionService.Session session = service.start(
                runId, 1, 1, 1, AgentToolNames.GITHUB_RELEASE_LIST,
                Map.of("query", "Spring AI"));
        session.succeed(Map.of("count", 2));

        assertThat(store.status).isEqualTo("SUCCEEDED");
        assertThat(store.toolName).isEqualTo(AgentToolNames.GITHUB_RELEASE_LIST);
        assertThat(store.idempotencyKey)
                .isEqualTo(runId + ":" + AgentToolNames.GITHUB_RELEASE_LIST + ":1:1");
        assertThat(store.requestPayload).contains("Spring AI");
        assertThat(store.resultPayload).contains("\"count\":2");
        assertThat(session.failIfRunning("LATE_FAILURE")).isFalse();
        assertThatThrownBy(() -> session.fail("LATE_FAILURE"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already finished");
    }

    @Test
    void shouldAllowFailureAuditWhenSuccessPersistenceThrows() {
        RecordingStore store = new RecordingStore();
        store.failSuccessPersistence = true;
        RegisteredToolExecutionService.Session session = service(store).start(
                UUID.randomUUID(), 1, 1, 1,
                AgentToolNames.GITHUB_RELEASE_LIST,
                Map.of("query", "Spring AI"));

        assertThatThrownBy(() -> session.succeed(Map.of("count", 2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated persistence failure");
        store.failSuccessPersistence = false;
        assertThat(session.failIfRunning("AUDIT_PERSISTENCE_ERROR")).isTrue();
        assertThat(store.status).isEqualTo("FAILED:AUDIT_PERSISTENCE_ERROR");
    }

    @Test
    void shouldRejectInvalidOutputAndAllowCallerToCloseAuditAsFailed() {
        RecordingStore store = new RecordingStore();
        RegisteredToolExecutionService.Session session = service(store).start(
                UUID.randomUUID(), 1, 1, 1,
                AgentToolNames.GITHUB_RELEASE_LIST,
                Map.of("query", "Spring AI"));

        assertThatThrownBy(() -> session.succeed(Map.of("unexpected", true)))
                .isInstanceOf(RegisteredToolExecutionService.ToolExecutionException.class)
                .extracting(exception ->
                        ((RegisteredToolExecutionService.ToolExecutionException) exception).code())
                .isEqualTo(RegisteredToolExecutionService.ErrorCode.OUTPUT_INVALID);
        assertThat(session.failIfRunning("TOOL_OUTPUT_INVALID")).isTrue();
        assertThat(store.status).isEqualTo("FAILED:TOOL_OUTPUT_INVALID");
    }

    @Test
    void shouldRejectInvalidInputBeforeStartingAudit() {
        RecordingStore store = new RecordingStore();

        assertThatThrownBy(() -> service(store).start(
                UUID.randomUUID(), 1, 1, 1,
                AgentToolNames.GITHUB_RELEASE_LIST,
                Map.of("unknown", true)))
                .hasMessageContaining("invalid input");
        assertThat(store.status).isNull();
    }

    private static RegisteredToolExecutionService service(RecordingStore store) {
        AgentToolDefinition definition = new AgentToolDefinition(
                AgentToolNames.GITHUB_RELEASE_LIST, 1, "List releases", true,
                AgentToolDefinition.AccessLevel.WORKSPACE_MEMBER,
                AgentToolDefinition.RiskLevel.READ_ONLY,
                AgentToolDefinition.ApprovalPolicy.NOT_REQUIRED,
                java.time.Duration.ofSeconds(10), 10_000,
                List.of(AgentToolDefinition.Parameter.string(
                        "query", "Question", true, 4_000)),
                List.of(AgentToolDefinition.Parameter.integer(
                        "count", "Result count", true, 0, 100)));
        return new RegisteredToolExecutionService(
                new AgentToolRegistry(List.of(definition)), store,
                new ObjectMapper().findAndRegisterModules());
    }

    private static final class RecordingStore implements AgentToolExecutionStore {
        private String status;
        private String toolName;
        private String idempotencyKey;
        private String requestPayload;
        private String resultPayload;
        private boolean failSuccessPersistence;

        @Override
        public void startTool(UUID runId, UUID stepId, UUID toolCallId, int stepNo,
                              String toolName, String idempotencyKey, String requestPayload,
                              Instant startedAt) {
            this.status = "RUNNING";
            this.toolName = toolName;
            this.idempotencyKey = idempotencyKey;
            this.requestPayload = requestPayload;
        }

        @Override
        public void succeedTool(UUID runId, UUID stepId, UUID toolCallId,
                                String resultPayload, long durationMs, Instant finishedAt) {
            if (failSuccessPersistence) {
                throw new IllegalStateException("simulated persistence failure");
            }
            this.status = "SUCCEEDED";
            this.resultPayload = resultPayload;
        }

        @Override
        public void failTool(UUID stepId, UUID toolCallId, String errorCode,
                             long durationMs, Instant finishedAt) {
            this.status = "FAILED:" + errorCode;
        }
    }
}
