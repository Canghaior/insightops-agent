package com.jundaodsj.insightops.server.auth;

import com.jundaodsj.insightops.infrastructure.identity.IdentityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LoginRateLimiter {
    private static final String SCOPE = "LOGIN";
    private final AuthProperties properties;
    private final IdentityRepository repository;
    private final Clock clock;
    private final ConcurrentHashMap<String, AttemptState> fallback = new ConcurrentHashMap<>();

    @Autowired
    public LoginRateLimiter(AuthProperties properties, IdentityRepository repository) {
        this(properties, repository, Clock.systemUTC());
    }

    LoginRateLimiter(AuthProperties properties, Clock clock) {
        this(properties, null, clock);
    }

    LoginRateLimiter(AuthProperties properties, IdentityRepository repository, Clock clock) {
        this.properties = properties;
        this.repository = repository;
        this.clock = clock;
    }

    public void check(String username, String remoteAddress) {
        String key = key(username, remoteAddress);
        Instant now = clock.instant();
        AttemptState state = load(key);
        if (state == null) return;
        if (state.lockedUntil() != null && state.lockedUntil().isAfter(now)) {
            throw new LoginRateLimitedException(Duration.between(now, state.lockedUntil()).toSeconds());
        }
        if (expired(state, now)) clear(key);
    }

    public synchronized void failed(String username, String remoteAddress) {
        String key = key(username, remoteAddress);
        Instant now = clock.instant();
        if (repository != null) {
            repository.recordRateFailure(SCOPE, key, now, properties.getLoginWindowMinutes(),
                    properties.getLoginMaxFailures(), properties.getLoginLockMinutes());
            return;
        }
        AttemptState current = fallback.get(key);
        AttemptState state = current == null || expired(current, now) ? new AttemptState(0, now, null) : current;
        int failures = state.failures() + 1;
        Instant lockedUntil = failures >= Math.max(1, properties.getLoginMaxFailures())
                ? now.plus(Duration.ofMinutes(Math.max(1, properties.getLoginLockMinutes()))) : null;
        fallback.put(key, new AttemptState(failures, state.windowStarted(), lockedUntil));
    }

    public void succeeded(String username, String remoteAddress) {
        clear(key(username, remoteAddress));
    }

    private AttemptState load(String key) {
        if (repository == null) return fallback.get(key);
        return repository.rateState(SCOPE, key).map(value -> new AttemptState(
                value.failures(), value.windowStartedAt(), value.lockedUntil())).orElse(null);
    }

    private void save(String key, AttemptState state) {
        if (repository == null) {
            fallback.put(key, state);
            return;
        }
        repository.saveRateState(SCOPE, key, new IdentityRepository.RateState(
                state.failures(), state.windowStarted(), state.lockedUntil(), clock.instant()));
    }

    private void clear(String key) {
        if (repository == null) fallback.remove(key);
        else repository.clearRateState(SCOPE, key);
    }

    private boolean expired(AttemptState state, Instant now) {
        if (state.lockedUntil() != null && state.lockedUntil().isAfter(now)) return false;
        return !state.windowStarted().plus(Duration.ofMinutes(
                Math.max(1, properties.getLoginWindowMinutes()))).isAfter(now);
    }

    private static String key(String username, String remoteAddress) {
        String normalizedUser = username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
        String normalizedAddress = remoteAddress == null ? "unknown" : remoteAddress.strip();
        return AuthService.hash("login:" + normalizedUser + '|' + normalizedAddress);
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
