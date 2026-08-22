package com.jundaodsj.insightops.server.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.agent.application.AgentCheckpointStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentCheckpointServiceTest {

    @Test
    void consumesOwnedCheckpointAndRestoresDurableEvidenceState() {
        AgentCheckpointStore store = mock(AgentCheckpointStore.class);
        AgentCheckpointService service = new AgentCheckpointService(
                store, new ObjectMapper().findAndRegisterModules());
        UUID checkpointId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID newRunId = UUID.randomUUID();
        AgentCheckpointStore.Checkpoint checkpoint = checkpoint(
                checkpointId, workspaceId, userId, "AVAILABLE",
                "{\"evidence\":[\"release v2\"],\"sourceUrls\":[\"https://example.test\"],"
                        + "\"citations\":[],\"executedSignatures\":[\"release:2\"]}");
        when(store.findOwned(checkpointId, workspaceId, userId)).thenReturn(Optional.of(checkpoint));
        when(store.consume(org.mockito.ArgumentMatchers.eq(checkpointId),
                org.mockito.ArgumentMatchers.eq(newRunId), any())).thenReturn(true);

        AgentCheckpointService.ResumeState resumed = service.resume(
                checkpointId, workspaceId, userId, newRunId);

        assertThat(resumed.evidence()).containsExactly("release v2");
        assertThat(resumed.sourceUrls()).containsExactly("https://example.test");
        assertThat(resumed.executedSignatures()).containsExactly("release:2");
        verify(store).consume(org.mockito.ArgumentMatchers.eq(checkpointId),
                org.mockito.ArgumentMatchers.eq(newRunId), any());
    }

    @Test
    void refusesAConsumedCheckpoint() {
        AgentCheckpointStore store = mock(AgentCheckpointStore.class);
        AgentCheckpointService service = new AgentCheckpointService(
                store, new ObjectMapper().findAndRegisterModules());
        UUID checkpointId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(store.findOwned(checkpointId, workspaceId, userId)).thenReturn(Optional.of(
                checkpoint(checkpointId, workspaceId, userId, "CONSUMED", "{}")));

        assertThatThrownBy(() -> service.resume(
                checkpointId, workspaceId, userId, UUID.randomUUID()))
                .isInstanceOf(AgentCheckpointService.CheckpointException.class)
                .extracting(error -> ((AgentCheckpointService.CheckpointException) error).errorCode())
                .isEqualTo("CHECKPOINT_ALREADY_CONSUMED");
    }

    @Test
    void restoresLatestSameRunCheckpointWithoutConsumingIt() {
        AgentCheckpointStore store = mock(AgentCheckpointStore.class);
        AgentCheckpointService service = new AgentCheckpointService(
                store, new ObjectMapper().findAndRegisterModules());
        UUID checkpointId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AgentCheckpointStore.Checkpoint checkpoint = new AgentCheckpointStore.Checkpoint(
                checkpointId, UUID.randomUUID(), runId, workspaceId, userId,
                2, "SAFE_POINT", "AVAILABLE",
                "{\"evidence\":[\"durable evidence\"],\"sourceUrls\":[],"
                        + "\"citations\":[],\"executedSignatures\":[\"knowledge:1\"]}",
                "{\"usedNodes\":3,\"usedToolAttempts\":2,\"usedModelTokens\":400,"
                        + "\"estimatedCostCny\":0.012000,\"status\":\"ACTIVE\","
                        + "\"exhaustionReason\":null}",
                Instant.parse("2026-08-22T00:00:00Z"), null);
        when(store.findLatestForRun(runId, workspaceId, userId))
                .thenReturn(Optional.of(checkpoint));

        AgentCheckpointService.RecoveryState restored = service.restoreForTakeover(
                null, runId, workspaceId, userId);

        assertThat(restored.checkpointId()).isEqualTo(checkpointId);
        assertThat(restored.resumeState().evidence()).containsExactly("durable evidence");
        assertThat(restored.resumeState().executedSignatures()).containsExactly("knowledge:1");
        assertThat(restored.budget().usedNodes()).isEqualTo(3);
        assertThat(restored.budget().estimatedCostCny()).isEqualByComparingTo("0.012000");
        verify(store, never()).consume(any(), any(), any());
    }

    private static AgentCheckpointStore.Checkpoint checkpoint(
            UUID id, UUID workspaceId, UUID userId, String status, String state) {
        return new AgentCheckpointStore.Checkpoint(
                id, UUID.randomUUID(), UUID.randomUUID(), workspaceId, userId,
                1, "SAFE_POINT", status, state, "{}", Instant.parse("2026-08-22T00:00:00Z"), null);
    }
}
