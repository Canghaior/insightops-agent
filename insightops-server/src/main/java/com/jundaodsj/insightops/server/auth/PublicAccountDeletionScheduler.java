package com.jundaodsj.insightops.server.auth;

import com.jundaodsj.insightops.infrastructure.identity.PublicAccountDeletionRepository;
import com.jundaodsj.insightops.knowledge.application.KnowledgeFileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.temporal.ChronoUnit;

@Component
public class PublicAccountDeletionScheduler {
    private static final Logger log = LoggerFactory.getLogger(PublicAccountDeletionScheduler.class);
    private final PublicAccountDeletionRepository repository;
    private final KnowledgeFileStorage storage;
    private final PersonalDataExportStorage exportStorage;
    private final Clock clock = Clock.systemUTC();
    public PublicAccountDeletionScheduler(PublicAccountDeletionRepository repository,
                                          KnowledgeFileStorage storage,
                                          PersonalDataExportStorage exportStorage) {
        this.repository = repository; this.storage = storage; this.exportStorage = exportStorage;
    }
    @Scheduled(initialDelayString = "${insightops.public-beta.deletion-initial-delay-ms:90000}",
            fixedDelayString = "${insightops.public-beta.deletion-poll-delay-ms:3600000}")
    public void process() {
        var now = clock.instant();
        repository.claimDue(now, now.minus(15, ChronoUnit.MINUTES), 25).forEach(userId -> {
            try {
                for (String key : repository.uploadStorageKeys(userId)) storage.delete(key);
                for (String key : repository.exportStorageKeys(userId)) exportStorage.delete(key);
                repository.complete(userId, clock.instant());
            } catch (Exception exception) {
                repository.fail(userId, exception.getClass().getSimpleName());
                log.warn("Public account purge failed userId={} errorType={}", userId,
                        exception.getClass().getSimpleName());
            }
        });
    }
}
