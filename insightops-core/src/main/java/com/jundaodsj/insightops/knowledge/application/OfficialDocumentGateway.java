package com.jundaodsj.insightops.knowledge.application;

import java.time.Duration;
import java.util.List;

public interface OfficialDocumentGateway {

    List<KnowledgeStore.DocumentPage> collect(
            KnowledgeStore.SourceTask source, CrawlOptions options, ProgressListener progressListener);

    @FunctionalInterface
    interface ProgressListener {
        void onProgress(KnowledgeStore.CollectionProgress progress);
    }

    record CrawlOptions(
            int maxPages, int maxDepth, int maxBytes, Duration requestTimeout,
            Duration requestDelay, int chunkMaxTokens, int chunkOverlapTokens) {
    }
}
