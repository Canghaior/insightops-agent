package com.jundaodsj.insightops.server.auth;

import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService implements ApplicationRunner {

    private final AccountWorkspaceStore store;
    private final AuthProperties properties;
    private final TotpService totp;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    @Autowired
    public AuthService(AccountWorkspaceStore store, AuthProperties properties, TotpService totp) {
        this(store, properties, totp, Clock.systemUTC());
    }

    AuthService(AccountWorkspaceStore store, AuthProperties properties, Clock clock) {
        this(store, properties, null, clock);
    }

    AuthService(AccountWorkspaceStore store, AuthProperties properties, TotpService totp, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.totp = totp;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        AuthProperties.Bootstrap bootstrap = properties.getBootstrap();
        if (!bootstrap.isEnabled()) {
            return;
        }
        validatePassword(bootstrap.getPassword());
        store.ensureBootstrapCredential(
                bootstrap.getUsername().trim(),
                bootstrap.getDisplayName().trim(),
                passwords.encode(bootstrap.getPassword()));
    }

    public LoginResult login(String username, String password) {
        return login(username, password, null, null, null);
    }

    public LoginResult login(String username, String password, String mfaCode,
                             String userAgent, String remoteAddress) {
        AccountWorkspaceStore.AccountRecord account = store.findForLogin(normalizeUsername(username))
                .filter(candidate -> passwordMatches(candidate, password))
                .orElseThrow(InvalidCredentialsException::new);
        if (totp != null && totp.enabled(account.userId())) {
            if (mfaCode == null || mfaCode.isBlank()) {
                throw new MfaRequiredException();
            }
            if (!totp.verify(account.userId(), mfaCode)) {
                throw new InvalidCredentialsException();
            }
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant createdAt = clock.instant();
        Instant expiresAt = createdAt.plus(properties.getSessionDays(), ChronoUnit.DAYS);
        store.saveSession(UUID.randomUUID(), account.userId(), account.workspaceId(), hash(token),
                truncate(userAgent, 500), hashAddress(remoteAddress), createdAt, expiresAt);
        return new LoginResult(token, expiresAt, account);
    }

    public Optional<AccountWorkspaceStore.AccountRecord> authenticate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return store.findBySessionTokenHash(hash(token), clock.instant());
    }

    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            store.revokeSession(hash(token), clock.instant());
        }
    }

    public void changePassword(
            AccountWorkspaceStore.AccountRecord account,
            String currentPassword,
            String newPassword) {
        if (!passwordMatches(account, currentPassword)) {
            throw new InvalidCredentialsException();
        }
        validatePassword(newPassword);
        store.changePassword(account.userId(), passwords.encode(newPassword), clock.instant());
    }

    public boolean passwordMatches(AccountWorkspaceStore.AccountRecord account, String password) {
        return password != null && passwords.matches(password, account.passwordHash());
    }

    String encodePassword(String password) {
        validatePassword(password);
        return passwords.encode(password);
    }

    public int cookieMaxAgeSeconds() {
        return Math.multiplyExact(properties.getSessionDays(), 86_400);
    }

    public boolean secureCookie() {
        return properties.isSecureCookie();
    }

    private static String normalizeUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new InvalidCredentialsException();
        }
        return username.trim();
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 10 || password.length() > 72
                || password.chars().noneMatch(Character::isUpperCase)
                || password.chars().noneMatch(Character::isLowerCase)
                || password.chars().noneMatch(Character::isDigit)) {
            throw new IllegalArgumentException(
                    "Password must be 10-72 characters and include upper-case, lower-case and digit characters");
        }
    }

    private static String truncate(String value, int maximum) {
        if (value == null || value.isBlank()) return null;
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private static String hashAddress(String address) {
        return address == null || address.isBlank() ? null : hash("ip:" + address.strip());
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record LoginResult(
            String token,
            Instant expiresAt,
            AccountWorkspaceStore.AccountRecord account) {
    }

    public static class InvalidCredentialsException extends RuntimeException {
        public InvalidCredentialsException() {
            super("Username, password or MFA code is incorrect");
        }
    }

    public static class MfaRequiredException extends RuntimeException {
        public MfaRequiredException() {
            super("MFA_REQUIRED");
        }
    }
}
