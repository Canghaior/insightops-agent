package com.jundaodsj.insightops.server.auth;

import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import com.jundaodsj.insightops.infrastructure.identity.IdentityRepository;
import com.jundaodsj.insightops.infrastructure.identity.IdentitySecretCipher;
import com.jundaodsj.insightops.infrastructure.identity.WorkspaceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class IdentityLifecycleService {
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]{1,253}$");
    private final IdentityRepository repository;
    private final WorkspaceRepository workspaces;
    private final IdentitySecretCipher cipher;
    private final IdentityProperties properties;
    private final AuthService authService;
    private final TotpService totp;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    @Autowired
    public IdentityLifecycleService(IdentityRepository repository, WorkspaceRepository workspaces,
                                    IdentitySecretCipher cipher, IdentityProperties properties,
                                    AuthService authService, TotpService totp) {
        this(repository, workspaces, cipher, properties, authService, totp, Clock.systemUTC());
    }

    IdentityLifecycleService(IdentityRepository repository, WorkspaceRepository workspaces,
                             IdentitySecretCipher cipher, IdentityProperties properties,
                             AuthService authService, TotpService totp, Clock clock) {
        this.repository = repository;
        this.workspaces = workspaces;
        this.cipher = cipher;
        this.properties = properties;
        this.authService = authService;
        this.totp = totp;
        this.clock = clock;
    }

    public SecuritySummary summary(UUID userId) {
        IdentityRepository.UserIdentity identity = repository.findIdentity(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return new SecuritySummary(identity.email(), identity.emailVerified(), identity.mfaEnabled(),
                identity.mfaEnabled() ? repository.unusedRecoveryCodes(userId) : 0,
                identity.deletionScheduledAt(), properties.getMail().isEnabled());
    }

    @Transactional
    public EmailRequest requestEmailChange(AccountWorkspaceStore.AccountRecord actor,
                                           String password, String email) {
        requirePassword(actor, password);
        String normalized = normalizeEmail(email);
        String canonical = email.trim();
        try {
            repository.setPendingEmail(actor.userId(), canonical, normalized, clock.instant());
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email address is already in use");
        }
        Token token = createToken(actor.userId(), "EMAIL_VERIFICATION",
                properties.getEmailVerificationMinutes());
        String link = publicUrl("/verify-email#token=" + token.raw());
        enqueue(canonical, "EMAIL_VERIFICATION", "Verify your InsightOps email",
                "Verify this email address for InsightOps:\n\n" + link
                        + "\n\nThe link expires soon and can be used once.");
        return new EmailRequest(properties.getMail().isEnabled(),
                properties.getMail().isEnabled() ? null : link, token.expiresAt());
    }

    @Transactional
    public void verifyEmail(String rawToken) {
        UUID userId = repository.consumeToken(hashToken(rawToken), "EMAIL_VERIFICATION", clock.instant())
                .orElseThrow(() -> invalidToken("Email verification link is invalid or expired"));
        repository.markEmailVerified(userId, clock.instant());
    }

    @Transactional
    public void requestPasswordReset(String email) {
        String normalized;
        try { normalized = normalizeEmail(email); }
        catch (ResponseStatusException exception) { return; }
        repository.findActiveUserByEmail(normalized).ifPresent(userId -> {
            Token token = createToken(userId, "PASSWORD_RESET", properties.getPasswordResetMinutes());
            String link = publicUrl("/reset-password#token=" + token.raw());
            enqueue(email.trim(), "PASSWORD_RESET", "Reset your InsightOps password",
                    "Reset your InsightOps password:\n\n" + link
                            + "\n\nThe link expires soon and can be used once."
                            + " If you did not request this, ignore this message.");
        });
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        String passwordHash;
        try {
            passwordHash = authService.encodePassword(newPassword);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
        UUID userId = repository.consumeToken(hashToken(rawToken), "PASSWORD_RESET", clock.instant())
                .orElseThrow(() -> invalidToken("Password reset link is invalid or expired"));
        repository.resetPassword(userId, passwordHash, clock.instant());
    }

    public List<IdentityRepository.SessionRecord> sessions(UUID userId, String rawToken) {
        return repository.listSessions(userId, AuthService.hash(rawToken), clock.instant());
    }

    public void revokeSession(UUID userId, UUID sessionId) {
        if (!repository.revokeSession(userId, sessionId, clock.instant())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session was not found");
        }
    }

    public int revokeOtherSessions(UUID userId, String rawToken) {
        return repository.revokeOtherSessions(userId, AuthService.hash(rawToken), clock.instant());
    }

    public TotpService.Setup beginMfa(AccountWorkspaceStore.AccountRecord actor, String password) {
        requirePassword(actor, password);
        return totp.begin(actor.userId(), actor.username());
    }

    public List<String> confirmMfa(AccountWorkspaceStore.AccountRecord actor, String code) {
        try { return totp.confirm(actor.userId(), code); }
        catch (TotpService.InvalidMfaCodeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    public void disableMfa(AccountWorkspaceStore.AccountRecord actor, String password, String code,
                           String rawSessionToken) {
        requirePassword(actor, password);
        if (!totp.enabled(actor.userId()) || !totp.verify(actor.userId(), code)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The authenticator or recovery code is invalid");
        }
        totp.disable(actor.userId());
        repository.revokeOtherSessions(actor.userId(), AuthService.hash(rawSessionToken), clock.instant());
    }

    @Transactional
    public Instant requestDeletion(AccountWorkspaceStore.AccountRecord actor,
                                   String password, String mfaCode) {
        requirePassword(actor, password);
        if (totp.enabled(actor.userId()) && !totp.verify(actor.userId(), mfaCode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MFA confirmation is required");
        }
        if (workspaces.soleOwnedWorkspaceCount(actor.userId()) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Transfer or archive every workspace where you are the only owner before deleting the account");
        }
        Instant scheduledAt = clock.instant().plus(properties.getDeletionGraceDays(), ChronoUnit.DAYS);
        repository.requestDeletion(actor.userId(), clock.instant(), scheduledAt);
        return scheduledAt;
    }

    public void cancelDeletion(AccountWorkspaceStore.AccountRecord actor, String password) {
        requirePassword(actor, password);
        if (!repository.cancelDeletion(actor.userId(), clock.instant())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No cancellable deletion request exists");
        }
    }

    private void requirePassword(AccountWorkspaceStore.AccountRecord actor, String password) {
        if (!authService.passwordMatches(actor, password)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        }
    }

    private Token createToken(UUID userId, String type, int minutes) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        Instant expiresAt = now.plus(Math.max(5, minutes), ChronoUnit.MINUTES);
        repository.saveToken(UUID.randomUUID(), userId, type, hashToken(raw), expiresAt, now);
        return new Token(raw, expiresAt);
    }

    private void enqueue(String recipient, String template, String subject, String body) {
        repository.enqueueMail(UUID.randomUUID(), recipient, template, subject,
                cipher.encrypt(body), clock.instant());
    }

    private String publicUrl(String path) {
        String base = properties.getPublicBaseUrl().replaceAll("/+$", "");
        return base + path;
    }

    static String normalizeEmail(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 320 || !EMAIL.matcher(normalized).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email address is invalid");
        }
        return normalized;
    }

    static String hashToken(String raw) {
        if (raw == null || raw.length() < 32 || raw.length() > 200) {
            throw invalidToken("Identity link is invalid or expired");
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static ResponseStatusException invalidToken(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record SecuritySummary(String email, boolean emailVerified, boolean mfaEnabled,
                                  int unusedRecoveryCodes, Instant deletionScheduledAt,
                                  boolean mailDeliveryEnabled) { }
    public record EmailRequest(boolean deliveryQueued, String manualVerificationLink,
                               Instant expiresAt) { }
    private record Token(String raw, Instant expiresAt) { }
}
