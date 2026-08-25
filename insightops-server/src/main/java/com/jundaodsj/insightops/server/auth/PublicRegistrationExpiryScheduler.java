package com.jundaodsj.insightops.server.auth;

import com.jundaodsj.insightops.infrastructure.identity.PublicBetaRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public class PublicRegistrationExpiryScheduler {
    private final PublicBetaRepository repository;
    private final Clock clock = Clock.systemUTC();

    public PublicRegistrationExpiryScheduler(PublicBetaRepository repository) {
        this.repository = repository;
    }

    @Scheduled(initialDelayString = "${insightops.public-beta.expiry-initial-delay-ms:60000}",
            fixedDelayString = "${insightops.public-beta.expiry-poll-delay-ms:900000}")
    public void expire() { repository.expirePending(clock.instant()); }
}
