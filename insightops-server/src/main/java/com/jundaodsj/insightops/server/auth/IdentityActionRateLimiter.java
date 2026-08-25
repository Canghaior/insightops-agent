package com.jundaodsj.insightops.server.auth;

import com.jundaodsj.insightops.infrastructure.identity.IdentityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component
public class IdentityActionRateLimiter {
    private static final int MAXIMUM = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final Duration LOCK = Duration.ofMinutes(15);
    private final IdentityRepository repository;
    private final Clock clock;

    @Autowired
    public IdentityActionRateLimiter(IdentityRepository repository) {
        this(repository, Clock.systemUTC());
    }

    IdentityActionRateLimiter(IdentityRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public synchronized void consume(String scope, String identity) {
        String key = AuthService.hash(scope + ':' + (identity == null ? "unknown" : identity.strip()));
        Instant now = clock.instant();
        IdentityRepository.RateState current = repository.rateState(scope, key).orElse(null);
        if (current != null && current.lockedUntil() != null && current.lockedUntil().isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests; try again later");
        }
        IdentityRepository.RateState result = repository.recordRateFailure(
                scope, key, now, (int) WINDOW.toMinutes(), MAXIMUM, (int) LOCK.toMinutes());
        if (result.failures() > MAXIMUM) throw new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS, "Too many requests; try again later");
    }
}
