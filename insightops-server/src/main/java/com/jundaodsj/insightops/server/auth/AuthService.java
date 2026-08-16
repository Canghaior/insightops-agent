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
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    @Autowired
    public AuthService(AccountWorkspaceStore store, AuthProperties properties) {
        this(store, properties, Clock.systemUTC());
    }

    AuthService(AccountWorkspaceStore store, AuthProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
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
        AccountWorkspaceStore.AccountRecord account = store.findForLogin(normalizeUsername(username))
                .filter(candidate -> password != null && passwords.matches(password, candidate.passwordHash()))
                .orElseThrow(InvalidCredentialsException::new);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant createdAt = clock.instant();
        Instant expiresAt = createdAt.plus(properties.getSessionDays(), ChronoUnit.DAYS);
        store.saveSession(UUID.randomUUID(), account.userId(), hash(token), createdAt, expiresAt);
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
        if (currentPassword == null || !passwords.matches(currentPassword, account.passwordHash())) {
            throw new InvalidCredentialsException();
        }
        validatePassword(newPassword);
        store.changePassword(account.userId(), passwords.encode(newPassword), clock.instant());
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
            super("Username or password is incorrect");
        }
    }
}
