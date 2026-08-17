package com.jundaodsj.insightops.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeEmbeddingScheduler {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeEmbeddingScheduler.class);
    private final KnowledgeEmbeddingRunner runner;
    private final KnowledgeEmbeddingProperties properties;

    public KnowledgeEmbeddingScheduler(KnowledgeEmbeddingRunner runner,
                                       KnowledgeEmbeddingProperties properties) {
        this.runner = runner;
        this.properties = properties;
    }

    @Scheduled(initialDelayString = "${insightops.knowledge.embedding.initial-delay-ms:15000}",
            fixedDelayString = "${insightops.knowledge.embedding.poll-delay-ms:10000}")
    public void embed() {
        if (!properties.isEnabled()) return;
        var result = runner.embedPendingChunks();
        if (result.prepared() > 0 || result.claimed() > 0) {
            log.info("Knowledge embedding completed: prepared={}, batches={}, claimed={}, succeeded={}, failed={}",
                    result.prepared(), result.batches(), result.claimed(), result.succeeded(), result.failed());
        }
    }
}
