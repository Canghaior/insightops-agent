package com.jundaodsj.insightops.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReleaseCollectionScheduler {
    private static final Logger log = LoggerFactory.getLogger(ReleaseCollectionScheduler.class);
    private final ReleaseCollectionRunner runner;
    private final ReleaseCollectionProperties properties;

    public ReleaseCollectionScheduler(
            ReleaseCollectionRunner runner,
            ReleaseCollectionProperties properties) {
        this.runner = runner;
        this.properties = properties;
    }

    @Scheduled(
            initialDelayString = "${insightops.collection.initial-delay-ms:3000}",
            fixedDelayString = "${insightops.collection.poll-delay-ms:30000}")
    public void collect() {
        if (!properties.isEnabled()) return;
        ReleaseCollectionRunner.CycleResult result = runner.collectDueProjects();
        if (result.claimedProjects() > 0) {
            log.info("Release collection completed: claimed={}, succeeded={}, failed={}, releases={}, newEvents={}",
                    result.claimedProjects(), result.succeededProjects(), result.failedProjects(),
                    result.fetchedReleases(), result.newEvents());
        }
    }
}
