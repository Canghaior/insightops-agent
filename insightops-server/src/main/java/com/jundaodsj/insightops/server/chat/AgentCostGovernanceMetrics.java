package com.jundaodsj.insightops.server.chat;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class AgentCostGovernanceMetrics {

    private final MeterRegistry registry;

    public AgentCostGovernanceMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void reservation(String outcome, String reason) {
        registry.counter("insightops.agent.cost.reservations",
                "outcome", safe(outcome), "reason", safe(reason)).increment();
    }

    public void settlement(String outcome) {
        registry.counter("insightops.agent.cost.settlements", "outcome", safe(outcome)).increment();
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "none" : value.toLowerCase();
    }
}
