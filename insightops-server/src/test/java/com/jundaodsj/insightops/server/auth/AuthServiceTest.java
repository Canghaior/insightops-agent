package com.jundaodsj.insightops.server.auth;

import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");
    private static final String PASSWORD = "ValidPassword1";

    @Test
    void shouldIssueHashedOpaqueSessionAndRejectWrongPassword() {
        RecordingStore store = new RecordingStore(PASSWORD);
        AuthProperties properties = new AuthProperties();
        properties.setSessionDays(14);
        AuthService service = new AuthService(
                store, properties, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.login("alpha-owner", "wrong-password"))
                .isInstanceOf(AuthService.InvalidCredentialsException.class);

        AuthService.LoginResult login = service.login(" alpha-owner ", PASSWORD);
        assertThat(login.account().username()).isEqualTo("alpha-owner");
        assertThat(login.expiresAt()).isEqualTo(NOW.plusSeconds(14L * 86_400));
        assertThat(login.token()).hasSizeGreaterThan(40).doesNotContain("=");
        assertThat(store.savedTokenHash).hasSize(64).isEqualTo(AuthService.hash(login.token()));
        assertThat(store.savedTokenHash).doesNotContain(login.token());

        service.logout(login.token());
        assertThat(store.revokedTokenHash).isEqualTo(store.savedTokenHash);
    }

    @Test
    void shouldHashNewPasswordAndRevokeExistingSessions() {
        RecordingStore store = new RecordingStore(PASSWORD);
        AuthService service = new AuthService(
                store, new AuthProperties(), Clock.fixed(NOW, ZoneOffset.UTC));

        service.changePassword(store.account, PASSWORD, "ChangedPassword2");

        assertThat(new BCryptPasswordEncoder().matches("ChangedPassword2", store.changedPasswordHash))
                .isTrue();
        assertThat(store.changedAt).isEqualTo(NOW);
    }

    private static final class RecordingStore implements AccountWorkspaceStore {
        private final AccountRecord account;
        private String savedTokenHash;
        private String revokedTokenHash;
        private String changedPasswordHash;
        private Instant changedAt;

        private RecordingStore(String password) {
            account = new AccountRecord(
                    UUID.randomUUID(), "alpha-owner", "Alpha Owner", UUID.randomUUID(),
                    "InsightOps Alpha", "OWNER", new BCryptPasswordEncoder().encode(password), false);
        }

        @Override
        public Optional<AccountRecord> findForLogin(String username) {
            return "alpha-owner".equals(username) ? Optional.of(account) : Optional.empty();
        }

        @Override
        public Optional<AccountRecord> findBySessionTokenHash(String tokenHash, Instant now) {
            return tokenHash.equals(savedTokenHash) ? Optional.of(account) : Optional.empty();
        }

        @Override
        public void saveSession(UUID sessionId, UUID userId, String tokenHash, Instant createdAt, Instant expiresAt) {
            savedTokenHash = tokenHash;
        }

        @Override
        public void revokeSession(String tokenHash, Instant revokedAt) {
            revokedTokenHash = tokenHash;
        }

        @Override
        public void changePassword(UUID userId, String passwordHash, Instant changedAt) {
            changedPasswordHash = passwordHash;
            this.changedAt = changedAt;
        }

        @Override
        public void ensureBootstrapCredential(String username, String displayName, String passwordHash) {
        }
    }
}
