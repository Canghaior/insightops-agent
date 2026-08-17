package com.jundaodsj.insightops.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class IntelligenceAnalysisScheduler {
    private static final Logger log= LoggerFactory.getLogger(IntelligenceAnalysisScheduler.class);
    private final IntelligenceAnalysisRunner runner;

    public IntelligenceAnalysisScheduler(IntelligenceAnalysisRunner runner){this.runner=runner;}

    @Scheduled(initialDelayString="${insightops.intelligence.initial-delay-ms:10000}",
            fixedDelayString="${insightops.intelligence.poll-delay-ms:30000}")
    public void analyze(){
        var result=runner.analyzeDueReleases();
        if(result.claimed()>0) log.info("Intelligence analysis completed: claimed={}, succeeded={}, failed={}",
                result.claimed(),result.succeeded(),result.failed());
    }

    @Scheduled(initialDelayString="${insightops.intelligence.digest-initial-delay-ms:15000}",
            fixedDelayString="${insightops.intelligence.digest-poll-delay-ms:300000}")
    public void digests(){int created=runner.refreshDigests();if(created>0)log.info("Intelligence digests created: {}",created);}
}
