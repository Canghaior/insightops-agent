package com.jundaodsj.insightops.worker;

import com.jundaodsj.insightops.report.application.ReportDeliveryGateway;
import com.jundaodsj.insightops.report.application.ReportDeliveryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportDeliveryRunnerTest {
    private static final Instant NOW = Instant.parse("2026-08-20T02:00:00Z");
    private final ReportDeliveryStore store = mock(ReportDeliveryStore.class);
    private final ReportDeliveryGateway gateway = mock(ReportDeliveryGateway.class);
    private final ReportDeliveryProperties properties = new ReportDeliveryProperties();
    private final ReportDeliveryStore.DeliveryTask task = task(1);
    private ReportDeliveryRunner runner;

    @BeforeEach
    void setUp() {
        runner = new ReportDeliveryRunner(store, gateway, properties, Clock.fixed(NOW, ZoneOffset.UTC));
        when(store.claimDueDeliveries(eq(NOW), any(), eq(5))).thenReturn(List.of(task));
    }

    @Test
    void completesSuccessfulWebhookDelivery() {
        when(gateway.deliver(task)).thenReturn(new ReportDeliveryGateway.DeliveryResult(204, 35));

        var result = runner.deliverDueReports();

        assertThat(result.claimed()).isEqualTo(1);
        assertThat(result.succeeded()).isEqualTo(1);
        verify(store).completeDelivery(task.deliveryId(), task.leaseToken(), 204, 35, NOW);
        verify(store, never()).failDelivery(any(), any(), any(), any(), any(),
                any(Long.class), any(), any(), any(Boolean.class));
    }

    @Test
    void retriesTransientFailureWithExponentialBackoff() {
        when(gateway.deliver(task)).thenThrow(new ReportDeliveryGateway.DeliveryException(
                "HTTP_503", "temporarily unavailable", 503, 44, true, null));

        var result = runner.deliverDueReports();

        assertThat(result.failed()).isEqualTo(1);
        verify(store).failDelivery(task.deliveryId(), task.leaseToken(), "HTTP_503",
                "temporarily unavailable", 503, 44, NOW, NOW.plus(Duration.ofMinutes(5)), false);
        verify(store, never()).completeDelivery(any(), any(), any(Integer.class), any(Long.class), any());
    }

    @Test
    void terminatesNonRetryableFailure() {
        var laterTask = task(2);
        when(store.claimDueDeliveries(eq(NOW), any(), eq(5))).thenReturn(List.of(laterTask));
        when(gateway.deliver(laterTask)).thenThrow(new ReportDeliveryGateway.DeliveryException(
                "HTTP_400", "invalid request", 400, 20, false, null));

        runner.deliverDueReports();

        verify(store).failDelivery(laterTask.deliveryId(), laterTask.leaseToken(), "HTTP_400",
                "invalid request", 400, 20, NOW, NOW.plus(Duration.ofMinutes(10)), true);
    }

    private static ReportDeliveryStore.DeliveryTask task(int attempts) {
        return new ReportDeliveryStore.DeliveryTask(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Weekly report",
                NOW.minus(Duration.ofDays(7)), NOW, 3, 1, "summary",
                "https://hooks.example.com/a", attempts, 3);
    }
}
