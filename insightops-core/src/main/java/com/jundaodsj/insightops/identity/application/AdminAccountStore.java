package com.jundaodsj.insightops.identity.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminAccountStore {

    List<ManagedUser> listUsers(UUID workspaceId);

    Optional<ManagedUser> findUser(UUID workspaceId, UUID userId);

    ManagedUser createUser(
            UUID userId,
            UUID workspaceId,
            String username,
            String displayName,
            String passwordHash,
            String systemRole,
            String workspaceRole,
            Instant now);

    Optional<ManagedUser> updateStatus(UUID workspaceId, UUID userId, String status, Instant now);

    Optional<ManagedUser> updateWorkspaceRole(
            UUID workspaceId,
            UUID userId,
            String workspaceRole,
            Instant now);

    boolean resetPassword(UUID workspaceId, UUID userId, String passwordHash, Instant now);

    void appendAudit(
            UUID auditId,
            UUID workspaceId,
            UUID actorUserId,
            UUID targetUserId,
            String action,
            String detailsJson,
            Instant now);

    List<AccountAudit> listAudit(UUID workspaceId, int limit);

    record ManagedUser(
            UUID userId,
            String username,
            String displayName,
            String status,
            String systemRole,
            String workspaceRole,
            boolean mustChangePassword,
            Instant createdAt,
            Instant updatedAt) {
    }

    record AccountAudit(
            UUID id,
            UUID actorUserId,
            String actorUsername,
            UUID targetUserId,
            String targetUsername,
            String action,
            String detailsJson,
            Instant createdAt) {
    }
}
