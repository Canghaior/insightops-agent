package com.jundaodsj.insightops.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReportDeliveryScheduler {
    private static final Logger log = LoggerFactory.getLogger(ReportDeliveryScheduler.class);
    private final ReportDeliveryRunner runner;

    public ReportDeliveryScheduler(ReportDeliveryRunner runner) { this.runner = runner; }

    @Scheduled(initialDelayString = "${insightops.delivery.initial-delay-ms:20000}",
            fixedDelayString = "${insightops.delivery.poll-delay-ms:30000}")
    public void deliver() {
        var result = runner.deliverDueReports();
        if (result.claimed() > 0) {
            log.info("Report delivery completed: claimed={}, succeeded={}, failed={}",
                    result.claimed(), result.succeeded(), result.failed());
        }
    }
}
