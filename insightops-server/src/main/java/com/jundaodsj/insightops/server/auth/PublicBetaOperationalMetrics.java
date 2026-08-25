package com.jundaodsj.insightops.server.auth;

import com.jundaodsj.insightops.infrastructure.identity.PublicBetaMonitoringRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class PublicBetaOperationalMetrics {
    public PublicBetaOperationalMetrics(MeterRegistry registry,
                                        PublicBetaMonitoringRepository repository,
                                        PublicBetaProperties properties,
                                        TencentSesProperties ses) {
        Gauge.builder("insightops_public_beta_config_enabled", properties,
                        value -> value.isEnabled() ? 1 : 0)
                .description("Public Beta deployment configuration is enabled").register(registry);
        Gauge.builder("insightops_public_beta_registration_slots_occupied", repository,
                        value -> safe(value::occupiedSlots))
                .description("Occupied public Beta registration slots").register(registry);
        Gauge.builder("insightops_public_beta_registration_capacity", properties,
                        value -> Math.max(1, value.getMaximumRegistrations()))
                .description("Configured public Beta registration capacity").register(registry);
        Gauge.builder("insightops_identity_mail_failed", repository,
                        value -> safe(value::failedMail))
                .description("Terminal identity mail outbox failures").register(registry);
        Gauge.builder("insightops_identity_mail_oldest_pending_seconds", repository,
                        value -> safe(value::oldestPendingMailSeconds))
                .description("Age of the oldest pending identity mail").register(registry);
        Gauge.builder("insightops_tencent_ses_ready", ses, value -> value.isReady() ? 1 : 0)
                .description("Tencent SES configuration readiness").register(registry);
    }

    private static double safe(Measurement measurement) {
        try { return measurement.value(); }
        catch (RuntimeException ignored) { return Double.NaN; }
    }
    @FunctionalInterface private interface Measurement { double value(); }
}
