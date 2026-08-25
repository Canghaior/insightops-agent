package com.jundaodsj.insightops.server.auth;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class PublicBetaMetrics {
    private final MeterRegistry registry;

    public PublicBetaMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void registration(String outcome) {
        Counter.builder("insightops_public_beta_registrations_total")
                .tag("outcome", safe(outcome)).register(registry).increment();
    }

    public void turnstile(String outcome) {
        Counter.builder("insightops_turnstile_verifications_total")
                .tag("outcome", safe(outcome)).register(registry).increment();
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
