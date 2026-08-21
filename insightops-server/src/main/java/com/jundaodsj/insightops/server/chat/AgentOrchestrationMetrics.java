package com.jundaodsj.insightops.server.chat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentOrchestrationMetrics {

    private final MeterRegistry registry;
    private final Map<String, Counter> nodeCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> exhaustionCounters = new ConcurrentHashMap<>();
    private final Timer parallelLayerDuration;
    private final DistributionSummary parallelLayerSize;

    public AgentOrchestrationMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.parallelLayerDuration = Timer.builder(
                        "insightops_agent_orchestration_layer_duration_seconds")
                .publishPercentileHistogram()
                .register(registry);
        this.parallelLayerSize = DistributionSummary.builder(
                        "insightops_agent_orchestration_layer_nodes")
                .baseUnit("nodes")
                .register(registry);
    }

    public void node(String outcome) {
        nodeCounters.computeIfAbsent(safe(outcome), key -> Counter.builder(
                        "insightops_agent_orchestration_nodes_total")
                .tag("outcome", key)
                .register(registry))
                .increment();
    }

    public void layer(int nodeCount, Duration duration) {
        parallelLayerSize.record(nodeCount);
        parallelLayerDuration.record(duration);
    }

    public void budgetExhausted(String reason) {
        exhaustionCounters.computeIfAbsent(safe(reason), key -> Counter.builder(
                        "insightops_agent_orchestration_budget_exhaustions_total")
                .tag("reason", key)
                .register(registry))
                .increment();
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.length() <= 64 ? value : value.substring(0, 64);
    }
}
