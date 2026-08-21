package com.jundaodsj.insightops.tool.application;

import com.jundaodsj.insightops.identity.application.ActorContext;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Durable human approval boundary for mutating Agent tools. */
public interface AgentToolApprovalStore {
    Approval request(Request command);
    List<Approval> list(ActorContext actor, String status);
    Optional<Approval> find(ActorContext actor, UUID approvalId);
    Approval approve(ActorContext actor, UUID approvalId, String comment, Instant now);
    Approval reject(ActorContext actor, UUID approvalId, String comment, Instant now);
    Approval compensate(ActorContext actor, UUID approvalId, String comment, Instant now);

    record Request(UUID id, UUID runId, UUID stepId, UUID toolCallId, UUID userId,
            UUID workspaceId, String toolName, String summary, String requestPayload,
            String idempotencyKey, Instant expiresAt, Instant createdAt) {}

    record Approval(UUID id, UUID runId, UUID toolCallId, String toolName, String summary,
            String status, String requestPayload, String resultPayload, String errorCode,
            String decisionComment, Instant expiresAt, Instant decidedAt, Instant executedAt,
            Instant compensatedAt, Instant createdAt, Instant updatedAt) {}

    final class ApprovalException extends RuntimeException {
        private final String code;
        public ApprovalException(String code, String message) { super(message); this.code = code; }
        public ApprovalException(String code, String message, Throwable cause) {
            super(message, cause); this.code = code;
        }
        public String code() { return code; }
    }
}
