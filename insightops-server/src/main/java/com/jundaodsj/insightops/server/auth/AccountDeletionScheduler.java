package com.jundaodsj.insightops.server.auth;

import com.jundaodsj.insightops.infrastructure.identity.IdentityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

@Component
public class AccountDeletionScheduler {
    private static final Logger log = LoggerFactory.getLogger(AccountDeletionScheduler.class);
    private final IdentityRepository repository;
    private final Clock clock;

    @Autowired
    public AccountDeletionScheduler(IdentityRepository repository) {
        this(repository, Clock.systemUTC());
    }

    AccountDeletionScheduler(IdentityRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${insightops.identity.deletion-initial-delay-ms:60000}",
            fixedDelayString = "${insightops.identity.deletion-poll-delay-ms:3600000}")
    public void process() {
        int completed = repository.completeDueDeletions(clock.instant(), 100);
        repository.purgeRateStates(clock.instant().minus(Duration.ofDays(2)));
        if (completed > 0) log.info("Completed account deletion requests count={}", completed);
    }
}
