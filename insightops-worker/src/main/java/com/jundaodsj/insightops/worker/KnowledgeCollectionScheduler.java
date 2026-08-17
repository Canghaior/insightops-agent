package com.jundaodsj.insightops.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeCollectionScheduler {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeCollectionScheduler.class);
    private final KnowledgeCollectionRunner runner;
    private final KnowledgeCollectionProperties properties;

    public KnowledgeCollectionScheduler(KnowledgeCollectionRunner runner,
                                        KnowledgeCollectionProperties properties) {
        this.runner = runner;
        this.properties = properties;
    }

    @Scheduled(initialDelayString = "${insightops.knowledge.collection.initial-delay-ms:20000}",
            fixedDelayString = "${insightops.knowledge.collection.poll-delay-ms:60000}")
    public void collect() {
        if (!properties.isEnabled()) return;
        var result = runner.collectDueSources();
        if (result.claimedSources() > 0) {
            log.info("Official document collection completed: claimed={}, succeeded={}, failed={}, pages={}, chunks={}",
                    result.claimedSources(), result.succeededSources(), result.failedSources(),
                    result.collectedPages(), result.createdChunks());
        }
    }
}
