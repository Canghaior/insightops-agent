package com.jundaodsj.insightops.server.auth;

import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import com.jundaodsj.insightops.infrastructure.identity.IdentityRepository;
import com.jundaodsj.insightops.infrastructure.identity.IdentitySecretCipher;
import com.jundaodsj.insightops.infrastructure.identity.PublicBetaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class PublicBetaService {
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{3,64}");
    private final PublicBetaRepository repository;
    private final PublicBetaProperties properties;
    private final HumanVerificationService humanVerification;
    private final IdentityProperties identityProperties;
    private final TencentSesProperties tencentSes;
    private final IdentityRepository identities;
    private final IdentitySecretCipher cipher;
    private final AuthService authService;
    private final PublicBetaMetrics metrics;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    @Autowired
    public PublicBetaService(PublicBetaRepository repository, PublicBetaProperties properties,
                             HumanVerificationService humanVerification,
                             IdentityProperties identityProperties, IdentityRepository identities,
                             IdentitySecretCipher cipher, AuthService authService,
                             PublicBetaMetrics metrics, TencentSesProperties tencentSes) {
        this(repository, properties, humanVerification, identityProperties, identities, cipher,
                authService, metrics, tencentSes, Clock.systemUTC());
    }

    PublicBetaService(PublicBetaRepository repository, PublicBetaProperties properties,
                      HumanVerificationService humanVerification,
                      IdentityProperties identityProperties, IdentityRepository identities,
                      IdentitySecretCipher cipher, AuthService authService,
                      PublicBetaMetrics metrics, TencentSesProperties tencentSes, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.humanVerification = humanVerification;
        this.identityProperties = identityProperties;
        this.identities = identities;
        this.cipher = cipher;
        this.authService = authService;
        this.metrics = metrics;
        this.clock = clock;
        this.tencentSes = tencentSes;
    }

    public Status status() {
        PublicBetaRepository.Control control = repository.control();
        PublicBetaRepository.Counts counts = repository.counts();
        String reason = readinessReason(control, counts);
        return new Status(reason == null, reason, properties.getTurnstile().getSiteKey(),
                Math.max(14, properties.getMinimumAge()), Math.max(1, properties.getMaximumRegistrations()),
                counts.active(), counts.pending(), counts.occupied(), control.runsEnabled(),
                control.statusMessage(), safe(properties.getOperatorName()), safe(properties.getContactEmail()),
                properties.getTermsVersion(), properties.getPrivacyVersion(),
                properties.getAcceptableUseVersion());
    }

    @Transactional
    public RegistrationResult register(RegistrationRequest request, String remoteAddress,
                                       String userAgent) {
        Status status = status();
        if (!status.registrationEnabled()) {
            metrics.registration("closed");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Public registration is unavailable: " + status.reason());
        }
        validateConsents(request);
        HumanVerificationService.VerificationResult verification =
                humanVerification.verify(request.turnstileToken(), remoteAddress);
        metrics.turnstile(verification.valid() ? "accepted" : verification.failureCode());
        if (!verification.valid()) {
            metrics.registration("human_verification_failed");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Human verification failed");
        }
        String username = request.username() == null ? "" : request.username().trim();
        if (!USERNAME.matcher(username).matches()) {
            throw badRequest("Username must be 3-64 characters using letters, digits, '.', '_' or '-'");
        }
        String displayName = required(request.displayName(), 128, "Display name");
        String normalizedEmail = IdentityLifecycleService.normalizeEmail(request.email());
        String passwordHash;
        try {
            passwordHash = authService.encodePassword(request.password());
        } catch (IllegalArgumentException exception) {
            throw badRequest(exception.getMessage());
        }
        UUID userId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(Math.max(1, properties.getPendingVerificationHours()), ChronoUnit.HOURS);
        List<PublicBetaRepository.Consent> consents = List.of(
                new PublicBetaRepository.Consent("TERMS", properties.getTermsVersion()),
                new PublicBetaRepository.Consent("PRIVACY", properties.getPrivacyVersion()),
                new PublicBetaRepository.Consent("ACCEPTABLE_USE", properties.getAcceptableUseVersion()),
                new PublicBetaRepository.Consent("AGE_CONFIRMATION", String.valueOf(Math.max(14, properties.getMinimumAge()))));
        try {
            PublicBetaRepository.Registration registration = repository.create(
                    new PublicBetaRepository.RegistrationCommand(userId, workspaceId, username,
                            displayName, request.email().trim(), normalizedEmail, passwordHash,
                            displayName + " Workspace", "beta-" + compact(workspaceId),
                            hash("ip", remoteAddress), hash("ua", userAgent), consents, expiresAt, now),
                    Math.max(1, properties.getMaximumRegistrations()));
            queueVerification(userId, request.email().trim(), now, expiresAt);
            metrics.registration("pending_verification");
            return new RegistrationResult(registration.slot(), expiresAt,
                    "Check your email and verify the account before signing in");
        } catch (PublicBetaRepository.RegistrationCapacityException exception) {
            metrics.registration("capacity_full");
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Public Beta capacity is full");
        } catch (DataIntegrityViolationException exception) {
            metrics.registration("duplicate");
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username or email already exists");
        }
    }

    public AdminStatus adminStatus(AccountWorkspaceStore.AccountRecord actor) {
        requireSystemAdmin(actor);
        return new AdminStatus(status(), repository.control());
    }

    public AdminStatus updateControl(AccountWorkspaceStore.AccountRecord actor,
                                     boolean registrationEnabled, boolean runsEnabled,
                                     String statusMessage) {
        requireSystemAdmin(actor);
        if (registrationEnabled && readinessReasonIgnoringControl(repository.counts()) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Complete public Beta configuration before enabling registration");
        }
        repository.updateControl(registrationEnabled, runsEnabled,
                optional(statusMessage, 500), actor.userId(), clock.instant());
        return adminStatus(actor);
    }

    private void queueVerification(UUID userId, String recipient, Instant now, Instant expiresAt) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        identities.saveToken(UUID.randomUUID(), userId, "EMAIL_VERIFICATION",
                IdentityLifecycleService.hashToken(raw), expiresAt, now);
        String base = identityProperties.getPublicBaseUrl().replaceAll("/+$", "");
        String link = base + "/verify-email#token=" + raw;
        identities.enqueueMail(UUID.randomUUID(), recipient, "EMAIL_VERIFICATION",
                "Verify your InsightOps email",
                cipher.encrypt("Verify this email address for InsightOps:\n\n" + link
                        + "\n\nThe link expires soon and can be used once."), now);
    }

    private String readinessReason(PublicBetaRepository.Control control, PublicBetaRepository.Counts counts) {
        String reason = readinessReasonIgnoringControl(counts);
        if (reason != null) return reason;
        if (!control.registrationEnabled()) return "REGISTRATION_SWITCH_OFF";
        return null;
    }

    private String readinessReasonIgnoringControl(PublicBetaRepository.Counts counts) {
        if (!properties.isEnabled()) return "PUBLIC_BETA_CONFIG_DISABLED";
        if (safe(properties.getOperatorName()).isEmpty() || safe(properties.getContactEmail()).isEmpty()) {
            return "PUBLIC_OPERATOR_DETAILS_MISSING";
        }
        if (!identityProperties.getMail().isEnabled()) return "MAIL_DELIVERY_NOT_READY";
        if (!humanVerification.ready()) return "TURNSTILE_NOT_READY";
        if (!tencentSes.isReady()) return "TENCENT_SES_NOT_READY";
        if (counts.occupied() >= Math.max(1, properties.getMaximumRegistrations())) return "CAPACITY_FULL";
        return null;
    }

    private void validateConsents(RegistrationRequest value) {
        if (value == null || !value.ageConfirmed() || !value.termsAccepted()
                || !value.privacyAccepted() || !value.acceptableUseAccepted()) {
            throw badRequest("Age confirmation and all public Beta agreements are required");
        }
    }

    private static void requireSystemAdmin(AccountWorkspaceStore.AccountRecord actor) {
        if (actor == null || !"SYSTEM_ADMIN".equals(actor.systemRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "System administrator access is required");
        }
    }

    private static String compact(UUID id) { return id.toString().replace("-", "").substring(0, 20); }
    private static String hash(String prefix, String value) {
        return AuthService.hash(prefix + ':' + (value == null ? "" : value.strip()));
    }
    private static String required(String value, int maximum, String label) {
        String safe = safe(value);
        if (safe.isEmpty() || safe.length() > maximum) throw badRequest(label + " is invalid");
        return safe;
    }
    private static String optional(String value, int maximum) {
        String safe = safe(value);
        if (safe.length() > maximum) throw badRequest("Status message is too long");
        return safe.isEmpty() ? null : safe;
    }
    private static String safe(String value) { return value == null ? "" : value.trim(); }
    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record RegistrationRequest(String username, String displayName, String email, String password,
                                      String turnstileToken, boolean ageConfirmed,
                                      boolean termsAccepted, boolean privacyAccepted,
                                      boolean acceptableUseAccepted) { }
    public record RegistrationResult(int registrationSlot, Instant verificationExpiresAt,
                                     String nextStep) { }
    public record Status(boolean registrationEnabled, String reason, String turnstileSiteKey,
                         int minimumAge, int maximumRegistrations, int activeRegistrations,
                         int pendingRegistrations, int occupiedSlots, boolean runsEnabled,
                         String statusMessage, String operatorName, String contactEmail,
                         String termsVersion, String privacyVersion, String acceptableUseVersion) { }
    public record AdminStatus(Status publicStatus, PublicBetaRepository.Control control) { }
}
