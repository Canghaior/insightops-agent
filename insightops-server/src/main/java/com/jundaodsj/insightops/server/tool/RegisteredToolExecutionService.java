package com.jundaodsj.insightops.server.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.tool.application.AgentToolExecutionStore;
import com.jundaodsj.insightops.tool.application.registry.AgentToolDefinition;
import com.jundaodsj.insightops.tool.application.registry.AgentToolRegistry;
import org.springframework.stereotype.Service;

import java.util.List;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class RegisteredToolExecutionService {

    private final AgentToolRegistry registry;
    private final AgentToolExecutionStore executionStore;
    private final ObjectMapper objectMapper;

    public RegisteredToolExecutionService(
            AgentToolRegistry registry,
            AgentToolExecutionStore executionStore,
            ObjectMapper objectMapper) {
        this.registry = registry;
        this.executionStore = executionStore;
        this.objectMapper = objectMapper;
    }

    public Session start(
            UUID runId,
            int stepNo,
            int round,
            int invocationNo,
            String toolName,
            Map<String, ?> input) {
        return start(runId, stepNo, round, invocationNo, toolName,
                AgentToolDefinition.AccessLevel.WORKSPACE_MEMBER, input);
    }

    public Session start(
            UUID runId,
            int stepNo,
            int round,
            int invocationNo,
            String toolName,
            AgentToolDefinition.AccessLevel grantedAccess,
            Map<String, ?> input) {
        Objects.requireNonNull(runId, "runId");
        positive(stepNo, "stepNo");
        positive(round, "round");
        positive(invocationNo, "invocationNo");
        AgentToolDefinition definition = registry.requireEnabled(toolName);
        Map<String, Object> validated = registry.validateInput(toolName, grantedAccess, input);
        UUID stepId = UUID.randomUUID();
        UUID toolCallId = UUID.randomUUID();
        Instant startedAt = Instant.now();
        executionStore.startTool(
                runId,
                stepId,
                toolCallId,
                stepNo,
                definition.name(),
                runId + ":" + definition.name() + ":" + round + ":" + invocationNo,
                json(validated),
                startedAt);
        return new Session(runId, stepId, toolCallId, definition, startedAt);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ToolExecutionException(
                    ErrorCode.SERIALIZATION_FAILED, "Unable to serialize tool audit payload", exception);
        }
    }

    private void validateOutput(AgentToolDefinition definition, String payload) {
        try {
            Map<String, Object> output = objectMapper.readValue(
                    payload, new TypeReference<Map<String, Object>>() { });
            definition.validateOutput(output);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new ToolExecutionException(
                    ErrorCode.OUTPUT_INVALID,
                    "Invalid output for tool " + definition.name() + ": "
                            + exception.getMessage(),
                    exception);
        }
    }

    private static void positive(int value, String label) {
        if (value < 1) throw new IllegalArgumentException(label + " must be positive");
    }

    public final class Session {
        private final UUID runId;
        private final UUID stepId;
        private final UUID toolCallId;
        private final AgentToolDefinition definition;
        private final Instant startedAt;
        private final AtomicBoolean finished = new AtomicBoolean();

        private Session(
                UUID runId,
                UUID stepId,
                UUID toolCallId,
                AgentToolDefinition definition,
                Instant startedAt) {
            this.runId = runId;
            this.stepId = stepId;
            this.toolCallId = toolCallId;
            this.definition = definition;
            this.startedAt = startedAt;
        }

        public UUID toolCallId() {
            return toolCallId;
        }

        public String toolName() {
            return definition.name();
        }

        public AgentToolDefinition definition() {
            return definition;
        }
        public Attempt startAttempt(int attemptNo) {
            positive(attemptNo, "attemptNo");
            UUID attemptId = UUID.randomUUID();
            Instant attemptStartedAt = Instant.now();
            executionStore.startAttempt(
                    attemptId, toolCallId, attemptNo, attemptStartedAt);
            return new Attempt(attemptId, attemptNo, attemptStartedAt);
        }

        public void finishAttempt(
                Attempt attempt,
                String status,
                String errorCode,
                boolean retryable,
                long retryDelayMs) {
            Objects.requireNonNull(attempt, "attempt");
            Instant attemptFinishedAt = Instant.now();
            executionStore.finishAttempt(
                    attempt.id(), status, errorCode, retryable,
                    Math.max(0, retryDelayMs),
                    Math.max(0, Duration.between(
                            attempt.startedAt(), attemptFinishedAt).toMillis()),
                    attemptFinishedAt);
        }

        public void succeed(Object result) {
            String payload = json(result);
            if (payload.length() > definition.maxResultCharacters()) {
                fail("TOOL_RESULT_TOO_LARGE");
                throw new ToolExecutionException(
                        ErrorCode.RESULT_TOO_LARGE,
                        "Tool result exceeds audit limit for " + definition.name());
            }
            validateOutput(definition, payload);
            completeOnce();
            Instant finishedAt = Instant.now();
            try {
                executionStore.succeedTool(
                        runId, stepId, toolCallId, payload,
                        durationMs(finishedAt), finishedAt);
            } catch (RuntimeException exception) {
                finished.set(false);
                throw exception;
            }
        }

        public void fail(String errorCode) {
            if (!failIfRunning(errorCode)) {
                throw new IllegalStateException("tool execution is already finished");
            }
        }

        public boolean failIfRunning(String errorCode) {
            return failIfRunning(errorCode, "FAILED");
        }

        public boolean failIfRunning(String errorCode, String status) {
            if (errorCode == null || errorCode.isBlank()) {
                throw new IllegalArgumentException("errorCode must not be blank");
            }
            if (!List.of("FAILED", "TIMED_OUT", "CANCELLED").contains(status)) {
                throw new IllegalArgumentException("unsupported terminal status: " + status);
            }
            if (!finished.compareAndSet(false, true)) {
                return false;
            }
            Instant finishedAt = Instant.now();
            try {
                executionStore.finishTool(
                        stepId, toolCallId, status, errorCode.strip(),
                        durationMs(finishedAt), finishedAt);
                return true;
            } catch (RuntimeException exception) {
                finished.set(false);
                throw exception;
            }
        }

        private void completeOnce() {
            if (!finished.compareAndSet(false, true)) {
                throw new IllegalStateException("tool execution is already finished");
            }
        }

        private long durationMs(Instant finishedAt) {
            return Math.max(0, Duration.between(startedAt, finishedAt).toMillis());
        }
    }

    public record Attempt(UUID id, int number, Instant startedAt) {
    }

    public enum ErrorCode {
        SERIALIZATION_FAILED,
        OUTPUT_INVALID,
        RESULT_TOO_LARGE
    }

    public static final class ToolExecutionException extends RuntimeException {
        private final ErrorCode code;

        public ToolExecutionException(ErrorCode code, String message) {
            super(message);
            this.code = code;
        }

        public ToolExecutionException(ErrorCode code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public ErrorCode code() {
            return code;
        }
    }
}
