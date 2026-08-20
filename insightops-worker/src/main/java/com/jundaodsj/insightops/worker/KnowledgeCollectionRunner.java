package com.jundaodsj.insightops.worker;

import com.jundaodsj.insightops.knowledge.application.DocumentCollectionException;
import com.jundaodsj.insightops.knowledge.application.KnowledgeCollectionLeaseLostException;
import com.jundaodsj.insightops.knowledge.application.KnowledgeStore;
import com.jundaodsj.insightops.knowledge.application.OfficialDocumentGateway;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
public class KnowledgeCollectionRunner {
    private final KnowledgeStore store;
    private final OfficialDocumentGateway gateway;
    private final KnowledgeCollectionProperties properties;
    private final Clock clock;
    private final MeterRegistry meters;

    @Autowired
    public KnowledgeCollectionRunner(KnowledgeStore store, OfficialDocumentGateway gateway,
                                     KnowledgeCollectionProperties properties, MeterRegistry meters) {
        this(store, gateway, properties, Clock.systemUTC(), meters);
    }

    public KnowledgeCollectionRunner(KnowledgeStore store, OfficialDocumentGateway gateway,
                                     KnowledgeCollectionProperties properties) {
        this(store, gateway, properties, Clock.systemUTC(), null);
    }

    KnowledgeCollectionRunner(KnowledgeStore store, OfficialDocumentGateway gateway,
                              KnowledgeCollectionProperties properties, Clock clock) {
        this(store, gateway, properties, clock, null);
    }

    KnowledgeCollectionRunner(KnowledgeStore store, OfficialDocumentGateway gateway,
                              KnowledgeCollectionProperties properties, Clock clock,
                              MeterRegistry meters) {
        this.store = store;
        this.gateway = gateway;
        this.properties = properties;
        this.clock = clock;
        this.meters = meters;
    }

    public CycleResult collectDueSources() {
        Instant startedAt = clock.instant();
        Duration lockDuration = Duration.ofMinutes(Math.max(1, properties.getLockMinutes()));
        var sources = store.claimDueSources(startedAt, lockDuration,
                Math.max(1, properties.getBatchSize()));
        int succeeded = 0;
        int failed = 0;
        int leaseLost = 0;
        int pages = 0;
        int chunks = 0;
        for (var source : sources) {
            try {
                var documents = gateway.collect(source, options(), progress ->
                        store.updateCollectionProgress(source, progress, clock.instant(), lockDuration));
                Instant completedAt = clock.instant();
                var result = store.completeSuccessfulSync(source, documents, completedAt,
                        completedAt.plus(Duration.ofHours(Math.max(1, source.syncIntervalHours()))));
                succeeded++;
                count(source, "succeeded");
                pages += result.pageCount();
                chunks += result.chunkCount();
            } catch (KnowledgeCollectionLeaseLostException exception) {
                leaseLost++;
                count(source, "lease_lost");
            } catch (DocumentCollectionException exception) {
                Instant failedAt = clock.instant();
                if (completeFailure(source, exception.code().name(), exception.getMessage(), failedAt,
                        failedAt.plus(retryDelay(source.consecutiveFailures(), exception.code())))) failed++;
                else leaseLost++;
                count(source, "failed");
            } catch (RuntimeException exception) {
                Instant failedAt = clock.instant();
                if (completeFailure(source, DocumentCollectionException.Code.INTERNAL_ERROR.name(),
                        exception.getClass().getSimpleName(), failedAt,
                        failedAt.plus(retryDelay(source.consecutiveFailures(),
                                DocumentCollectionException.Code.INTERNAL_ERROR)))) failed++;
                else leaseLost++;
                count(source, "failed");
            }
        }
        return new CycleResult(sources.size(), succeeded, failed, leaseLost,
                pages, chunks, startedAt, clock.instant());
    }

    private boolean completeFailure(KnowledgeStore.SourceTask source, String errorCode,
                                    String errorMessage, Instant failedAt, Instant nextRetryAt) {
        try {
            store.completeFailedSync(source, errorCode, errorMessage, failedAt, nextRetryAt);
            return true;
        } catch (KnowledgeCollectionLeaseLostException exception) {
            return false;
        }
    }

    private void count(KnowledgeStore.SourceTask source, String result) {
        if (meters == null) return;
        meters.counter("insightops.knowledge.collection.sources",
                "result", result, "source_type", source.sourceType()).increment();
    }

    private OfficialDocumentGateway.CrawlOptions options() {
        int maxTokens = Math.max(100, properties.getChunkMaxTokens());
        return new OfficialDocumentGateway.CrawlOptions(
                Math.max(1, properties.getMaxPagesPerSource()),
                Math.max(0, properties.getMaxDepth()),
                Math.max(65_536, properties.getMaxBytesPerPage()),
                Duration.ofSeconds(Math.max(1, properties.getRequestTimeoutSeconds())),
                Duration.ofMillis(Math.max(0, properties.getRequestDelayMs())),
                maxTokens,
                Math.min(Math.max(0, properties.getChunkOverlapTokens()), maxTokens / 2));
    }

    private static Duration retryDelay(int previousFailures, DocumentCollectionException.Code code) {
        if (code == DocumentCollectionException.Code.VALIDATION_ERROR
                || code == DocumentCollectionException.Code.UNSUPPORTED_CONTENT
                || code == DocumentCollectionException.Code.CONTENT_TOO_LARGE) {
            return Duration.ofHours(24);
        }
        long minutes = Math.min(240, 15L * (1L << Math.min(previousFailures, 4)));
        return Duration.ofMinutes(minutes);
    }

    public record CycleResult(int claimedSources, int succeededSources, int failedSources,
                              int leaseLostSources,
                              int collectedPages, int createdChunks,
                              Instant startedAt, Instant finishedAt) { }
}
