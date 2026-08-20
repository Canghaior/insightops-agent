package com.jundaodsj.insightops.worker;

import com.jundaodsj.insightops.report.application.ReportDeliveryGateway;
import com.jundaodsj.insightops.report.application.ReportDeliveryStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
public class ReportDeliveryRunner {
    private final ReportDeliveryStore store;
    private final ReportDeliveryGateway gateway;
    private final ReportDeliveryProperties properties;
    private final Clock clock;

    @Autowired
    public ReportDeliveryRunner(ReportDeliveryStore store, ReportDeliveryGateway gateway,
                                ReportDeliveryProperties properties) {
        this(store, gateway, properties, Clock.systemUTC());
    }

    ReportDeliveryRunner(ReportDeliveryStore store, ReportDeliveryGateway gateway,
                         ReportDeliveryProperties properties, Clock clock) {
        this.store = store;
        this.gateway = gateway;
        this.properties = properties;
        this.clock = clock;
    }

    public CycleResult deliverDueReports() {
        Instant started = clock.instant();
        if (!properties.isEnabled()) return new CycleResult(0, 0, 0, started, clock.instant());
        var tasks = store.claimDueDeliveries(started,
                Duration.ofMinutes(Math.max(1, properties.getLeaseMinutes())),
                Math.max(1, properties.getBatchSize()));
        int succeeded = 0, failed = 0;
        for (var task : tasks) {
            ReportDeliveryGateway.DeliveryResult result;
            try {
                result = gateway.deliver(task);
            } catch (ReportDeliveryGateway.DeliveryException exception) {
                failed++;
                Instant failedAt = clock.instant();
                boolean terminal = !exception.retryable()
                        || task.attempts() >= Math.min(task.maxAttempts(), properties.getMaxRetries() + 1);
                long backoffMinutes = Math.min(60, 5L * (1L << Math.min(Math.max(0, task.attempts() - 1), 3)));
                store.failDelivery(task.deliveryId(), task.leaseToken(), exception.code(), exception.getMessage(),
                        exception.responseCode(), exception.durationMs(), failedAt,
                        failedAt.plus(Duration.ofMinutes(backoffMinutes)), terminal);
                continue;
            } catch (RuntimeException exception) {
                failed++;
                Instant failedAt = clock.instant();
                boolean terminal = task.attempts() >= Math.min(task.maxAttempts(), properties.getMaxRetries() + 1);
                store.failDelivery(task.deliveryId(), task.leaseToken(), "INTERNAL_ERROR", exception.getMessage(),
                        null, 0, failedAt, failedAt.plus(Duration.ofMinutes(5)), terminal);
                continue;
            }
            store.completeDelivery(task.deliveryId(), task.leaseToken(), result.responseCode(),
                    result.durationMs(), clock.instant());
            succeeded++;
        }
        return new CycleResult(tasks.size(), succeeded, failed, started, clock.instant());
    }

    public record CycleResult(int claimed, int succeeded, int failed,
                              Instant startedAt, Instant finishedAt) { }
}
