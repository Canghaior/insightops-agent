package com.jundaodsj.insightops.conversation.application;

import com.jundaodsj.insightops.identity.application.ActorContext;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Durable queue, lease fencing and replayable event log for ordinary chat Agent runs. */
public interface DurableChatRunStore {

    void enqueue(WorkDraft draft, String startedEventJson);

    List<WorkLease> claim(
            String workerId, int limit, int maxAttempts, Duration leaseDuration, Instant now);

    LeaseControl renewLease(
            UUID runId, UUID leaseToken, Duration leaseDuration, Instant now);

    AttemptPreparation prepareAttempt(UUID runId, UUID leaseToken, Instant now);

    Optional<Long> appendEvent(
            UUID runId, UUID leaseToken, String eventType, String payloadJson, Instant now);

    boolean requestCancel(ActorContext actor, UUID runId, Instant requestedAt);

    boolean ownsWork(ActorContext actor, UUID runId);

    List<StoredEvent> events(ActorContext actor, UUID runId, long afterSequence, int limit);

    Optional<WorkView> findOwned(ActorContext actor, UUID runId);

    boolean markTerminal(
            UUID runId, UUID leaseToken, String status, String failureCode,
            String terminalEventType, String terminalEventJson, Instant finishedAt);

    QueueSnapshot queueSnapshot(Instant now);

    record WorkDraft(
            UUID runId,
            UUID workspaceId,
            UUID ownerUserId,
            UUID sessionId,
            String traceId,
            boolean systemAdmin,
            String accessLevel,
            String userPrompt,
            String contextualPrompt,
            UUID resumeCheckpointId,
            int maxAttempts,
            Instant createdAt) {
    }

    record WorkLease(
            UUID runId,
            UUID workspaceId,
            UUID ownerUserId,
            UUID sessionId,
            String traceId,
            boolean systemAdmin,
            String accessLevel,
            String userPrompt,
            String contextualPrompt,
            UUID resumeCheckpointId,
            UUID recoveryCheckpointId,
            UUID leaseToken,
            String workerId,
            int attemptCount,
            int maxAttempts,
            boolean reclaimed,
            Duration reclaimDelay,
            Instant leaseExpiresAt) {
    }

    record AttemptPreparation(UUID recoveryCheckpointId, boolean recovered) {
    }

    enum LeaseControl {
        ACTIVE,
        CANCEL_REQUESTED,
        LOST
    }

    record StoredEvent(
            long sequence,
            String eventType,
            String payloadJson,
            Instant createdAt) {
    }

    record WorkView(
            UUID runId,
            String status,
            int attemptCount,
            int maxAttempts,
            String claimedBy,
            Instant heartbeatAt,
            Instant leaseExpiresAt,
            Instant cancelRequestedAt,
            UUID recoveryCheckpointId,
            String failureCode,
            Instant updatedAt) {
        public boolean terminal() {
            return "PAUSED".equals(status) || "SUCCEEDED".equals(status)
                    || "FAILED".equals(status) || "CANCELLED".equals(status);
        }
    }

    record QueueSnapshot(
            long queued,
            long running,
            long expiredLeases,
            long oldestQueuedAgeSeconds,
            long oldestHeartbeatAgeSeconds) {
    }
}
