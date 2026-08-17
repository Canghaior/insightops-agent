package com.jundaodsj.insightops.worker;

import com.jundaodsj.insightops.infrastructure.model.DeepSeekCostEstimator;
import com.jundaodsj.insightops.intelligence.application.IntelligenceStore;
import com.jundaodsj.insightops.model.application.ModelCallException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
public class IntelligenceAnalysisRunner {
    private final IntelligenceStore store;
    private final ReleaseIntelligenceAnalyzer analyzer;
    private final DeepSeekCostEstimator costEstimator;
    private final IntelligenceAnalysisProperties properties;
    private final Clock clock;

    @Autowired
    public IntelligenceAnalysisRunner(IntelligenceStore store, ReleaseIntelligenceAnalyzer analyzer,
                                      DeepSeekCostEstimator costEstimator, IntelligenceAnalysisProperties properties) {
        this(store, analyzer, costEstimator, properties, Clock.systemUTC());
    }

    IntelligenceAnalysisRunner(IntelligenceStore store, ReleaseIntelligenceAnalyzer analyzer,
                               DeepSeekCostEstimator costEstimator, IntelligenceAnalysisProperties properties,
                               Clock clock) {
        this.store = store; this.analyzer = analyzer; this.costEstimator = costEstimator;
        this.properties = properties; this.clock = clock;
    }

    public CycleResult analyzeDueReleases() {
        Instant started = clock.instant();
        if (!properties.isEnabled() || !analyzer.available()) return new CycleResult(0,0,0,started,clock.instant());
        var tasks = store.claimDueAnalyses(started, Duration.ofMinutes(Math.max(1, properties.getLockMinutes())),
                Math.max(1, properties.getBatchSize()), Math.max(1, properties.getDailyLimit()));
        int succeeded=0, failed=0;
        for (var task : tasks) {
            try {
                var analyzed = analyzer.analyze(task);
                var estimate = costEstimator.estimate(analyzed.response().usage()).orElse(null);
                store.completeAnalysis(task, analyzed.result(), new IntelligenceStore.ModelAudit(
                        analyzed.response().provider(), analyzed.response().model(), analyzed.response().usage(),
                        estimate == null ? null : estimate.cny(),
                        estimate == null ? null : estimate.pricingEffectiveDate()), clock.instant());
                succeeded++;
            } catch (RuntimeException exception) {
                failed++;
                Instant failedAt=clock.instant();
                boolean terminal=task.attempts() >= Math.min(task.maxAttempts(), Math.max(1, properties.getMaxRetries()+1))
                        || exception instanceof ReleaseIntelligenceAnalyzer.InvalidAnalysisException;
                String code=exception instanceof ModelCallException model ? model.code().name()
                        : exception instanceof ReleaseIntelligenceAnalyzer.InvalidAnalysisException ? "INVALID_OUTPUT"
                        : "INTERNAL_ERROR";
                store.failAnalysis(task,code,exception.getMessage(),failedAt,
                        failedAt.plus(Duration.ofMinutes(Math.min(60,5L*(1L<<Math.min(task.attempts()-1,3))))),terminal);
            }
        }
        return new CycleResult(tasks.size(),succeeded,failed,started,clock.instant());
    }

    public int refreshDigests() { return store.refreshDueDigests(clock.instant()); }

    public record CycleResult(int claimed,int succeeded,int failed,Instant startedAt,Instant finishedAt){}
}
