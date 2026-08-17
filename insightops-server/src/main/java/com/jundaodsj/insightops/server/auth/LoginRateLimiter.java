package com.jundaodsj.insightops.server.auth;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LoginRateLimiter {
    private final AuthProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<String, AttemptState> attempts = new ConcurrentHashMap<>();

    public LoginRateLimiter(AuthProperties properties) {
        this(properties, Clock.systemUTC());
    }

    LoginRateLimiter(AuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public void check(String username, String remoteAddress) {
        String key = key(username, remoteAddress);
        Instant now = clock.instant();
        AttemptState state = attempts.get(key);
        if (state == null) return;
        if (state.lockedUntil() != null && state.lockedUntil().isAfter(now)) {
            throw new LoginRateLimitedException(Duration.between(now, state.lockedUntil()).toSeconds());
        }
        if (expired(state, now)) attempts.remove(key, state);
    }

    public void failed(String username, String remoteAddress) {
        String key = key(username, remoteAddress);
        Instant now = clock.instant();
        attempts.compute(key, (ignored, current) -> {
            AttemptState state = current == null || expired(current, now)
                    ? new AttemptState(0, now, null) : current;
            int failures = state.failures() + 1;
            int maximum = Math.max(1, properties.getLoginMaxFailures());
            Instant lockedUntil = failures >= maximum
                    ? now.plus(Duration.ofMinutes(Math.max(1, properties.getLoginLockMinutes())))
                    : null;
            return new AttemptState(failures, state.windowStarted(), lockedUntil);
        });
    }

    public void succeeded(String username, String remoteAddress) {
        attempts.remove(key(username, remoteAddress));
    }

    private boolean expired(AttemptState state, Instant now) {
        if (state.lockedUntil() != null && state.lockedUntil().isAfter(now)) return false;
        return !state.windowStarted().plus(Duration.ofMinutes(
                Math.max(1, properties.getLoginWindowMinutes()))).isAfter(now);
    }

    private static String key(String username, String remoteAddress) {
        String normalizedUser = username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
        String normalizedAddress = remoteAddress == null ? "unknown" : remoteAddress.strip();
        return normalizedUser + '|' + normalizedAddress;
    }

    private record AttemptState(int failures, Instant windowStarted, Instant lockedUntil) { }

    public static class LoginRateLimitedException extends RuntimeException {
        private final long retryAfterSeconds;

        LoginRateLimitedException(long retryAfterSeconds) {
            super("Too many login attempts; try again later");
            this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
        }

        public long retryAfterSeconds() { return retryAfterSeconds; }
    }
}
