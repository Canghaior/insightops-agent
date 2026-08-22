package com.jundaodsj.insightops.server.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.agent.application.AgentCheckpointStore;
import com.jundaodsj.insightops.agent.application.AgentOrchestrationStore;
import com.jundaodsj.insightops.conversation.application.ChatCitation;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class AgentCheckpointService {

    private final AgentCheckpointStore store;
    private final ObjectMapper objectMapper;

    public AgentCheckpointService(AgentCheckpointStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    public boolean requestPause(UUID workspaceId, UUID userId, UUID runId) {
        return store.requestPause(workspaceId, userId, runId, Instant.now());
    }

    public boolean pauseRequested(UUID runId) {
        return store.control(runId) == AgentCheckpointStore.ControlState.PAUSE_REQUESTED;
    }

    public AgentCheckpointStore.Checkpoint checkpoint(
            UUID planId, UUID runId, UUID workspaceId, UUID userId, String reason,
            List<String> evidence, Set<String> sources, List<ChatCitation> citations,
            Set<String> executedSignatures, AgentOrchestrationStore.BudgetSnapshot budget) {
        ResumeState state = new ResumeState(
                evidence == null ? List.of() : List.copyOf(evidence),
                sources == null ? List.of() : List.copyOf(sources),
                citations == null ? List.of() : List.copyOf(citations),
                executedSignatures == null ? List.of() : List.copyOf(executedSignatures));
        Instant now = Instant.now();
        return store.save(new AgentCheckpointStore.CheckpointDraft(
                UUID.randomUUID(), planId, runId, workspaceId, userId, reason,
                json(state), json(budget), now));
    }

    public ResumeState resume(UUID checkpointId, UUID workspaceId, UUID userId, UUID newRunId) {
        AgentCheckpointStore.Checkpoint checkpoint = store.findOwned(
                checkpointId, workspaceId, userId).orElseThrow(
                () -> new CheckpointException("CHECKPOINT_NOT_FOUND"));
        if (!"AVAILABLE".equals(checkpoint.status())) {
            throw new CheckpointException("CHECKPOINT_ALREADY_CONSUMED");
        }
        if (!store.consume(checkpointId, newRunId, Instant.now())) {
            throw new CheckpointException("CHECKPOINT_CONCURRENTLY_CONSUMED");
        }
        try { return objectMapper.readValue(checkpoint.stateJson(), ResumeState.class); }
        catch (JsonProcessingException exception) {
            throw new CheckpointException("CHECKPOINT_STATE_INVALID", exception);
        }
    }

    public RecoveryState restoreForTakeover(
            UUID checkpointId, UUID runId, UUID workspaceId, UUID userId) {
        AgentCheckpointStore.Checkpoint checkpoint = checkpointId == null
                ? store.findLatestForRun(runId, workspaceId, userId).orElseThrow(
                        () -> new CheckpointException("RECOVERY_CHECKPOINT_NOT_FOUND"))
                : store.findOwned(checkpointId, workspaceId, userId).orElseThrow(
                        () -> new CheckpointException("RECOVERY_CHECKPOINT_NOT_FOUND"));
        if (!runId.equals(checkpoint.sourceRunId()) || !"AVAILABLE".equals(checkpoint.status())) {
            throw new CheckpointException("RECOVERY_CHECKPOINT_INVALID");
        }
        try {
            return new RecoveryState(
                    checkpoint.id(),
                    objectMapper.readValue(checkpoint.stateJson(), ResumeState.class),
                    objectMapper.readValue(
                            checkpoint.budgetJson(), AgentOrchestrationStore.BudgetSnapshot.class));
        }
        catch (JsonProcessingException exception) {
            throw new CheckpointException("RECOVERY_CHECKPOINT_STATE_INVALID", exception);
        }
    }

    public void linkResume(UUID planId, UUID checkpointId) {
        store.linkResume(planId, checkpointId, Instant.now());
    }

    public void markPaused(UUID planId, UUID checkpointId) {
        store.markPaused(planId, checkpointId, Instant.now());
    }

    public void revision(UUID planId, int version, String reason, Object graph) {
        store.recordRevision(planId, version, reason, json(graph), Instant.now());
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) {
            throw new CheckpointException("CHECKPOINT_SERIALIZATION_FAILED", exception);
        }
    }

    public record ResumeState(
            List<String> evidence,
            List<String> sourceUrls,
            List<ChatCitation> citations,
            List<String> executedSignatures) {
        public ResumeState {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            sourceUrls = sourceUrls == null ? List.of() : List.copyOf(sourceUrls);
            citations = citations == null ? List.of() : List.copyOf(citations);
            executedSignatures = executedSignatures == null ? List.of() : List.copyOf(executedSignatures);
        }

        public LinkedHashSet<String> sourceSet() { return new LinkedHashSet<>(sourceUrls); }
        public LinkedHashSet<String> signatureSet() { return new LinkedHashSet<>(executedSignatures); }
    }

    public record RecoveryState(
            UUID checkpointId,
            ResumeState resumeState,
            AgentOrchestrationStore.BudgetSnapshot budget) {
    }

    public static final class CheckpointException extends RuntimeException {
        private final String errorCode;
        public CheckpointException(String errorCode) { super(errorCode); this.errorCode = errorCode; }
        public CheckpointException(String errorCode, Throwable cause) {
            super(errorCode, cause); this.errorCode = errorCode;
        }
        public String errorCode() { return errorCode; }
    }
}
