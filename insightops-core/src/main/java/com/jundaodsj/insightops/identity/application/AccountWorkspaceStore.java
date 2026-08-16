package com.jundaodsj.insightops.identity.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AccountWorkspaceStore {

    Optional<AccountRecord> findForLogin(String username);

    Optional<AccountRecord> findBySessionTokenHash(String tokenHash, Instant now);

    void saveSession(UUID sessionId, UUID userId, String tokenHash, Instant createdAt, Instant expiresAt);

    void revokeSession(String tokenHash, Instant revokedAt);

    void changePassword(UUID userId, String passwordHash, Instant changedAt);

    void ensureBootstrapCredential(String username, String displayName, String passwordHash);

    record AccountRecord(
            UUID userId,
            String username,
            String displayName,
            UUID workspaceId,
            String workspaceName,
            String role,
            String passwordHash,
            boolean mustChangePassword) {

        public ActorContext actor() {
            return new ActorContext(userId, workspaceId);
        }
    }
}
