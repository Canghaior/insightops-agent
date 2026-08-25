package com.jundaodsj.insightops.server.auth;

import com.jundaodsj.insightops.infrastructure.identity.PublicBetaActivationRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public class PublicBetaActivationScheduler {
    private final PublicBetaActivationRepository repository;
    private final Clock clock = Clock.systemUTC();

    public PublicBetaActivationScheduler(PublicBetaActivationRepository repository) {
        this.repository = repository;
    }

    @Scheduled(initialDelayString = "${insightops.public-beta.activation-initial-delay-ms:1000}",
            fixedDelayString = "${insightops.public-beta.activation-poll-delay-ms:2000}")
    public void activate() { repository.activateVerifiedRegistrations(clock.instant()); }
}
