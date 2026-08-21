package com.jundaodsj.insightops.server.tool;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

@Component
public class AgentToolOperationalMetrics {

    private final MeterRegistry registry;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();

    public AgentToolOperationalMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void logicalCall(String toolName, String outcome) {
        counter("insightops_agent_tool_calls_total", toolName, outcome).increment();
    }

    public void attempt(String toolName, String outcome) {
        counter("insightops_agent_tool_attempts_total", toolName, outcome).increment();
    }

    public void retry(String toolName, String reason) {
        counter("insightops_agent_tool_retries_total", toolName, safe(reason)).increment();
    }

    public void timeout(String toolName) {
        counter("insightops_agent_tool_timeouts_total", toolName, "timeout").increment();
    }

    public void circuitRejected(String group) {
        Counter.builder("insightops_agent_tool_circuit_rejections_total")
                .tag("group", safe(group))
                .register(registry)
                .increment();
    }

    public void duration(String toolName, Duration duration) {
        timers.computeIfAbsent(safe(toolName), name -> Timer.builder(
                        "insightops_agent_tool_duration_seconds")
                .tag("tool", name)
                .publishPercentileHistogram()
                .register(registry))
                .record(duration);
    }

    public void registerCircuitState(String group, IntSupplier state) {
        Gauge.builder("insightops_agent_tool_circuit_state", state, IntSupplier::getAsInt)
                .tag("group", safe(group))
                .description("0=CLOSED, 1=HALF_OPEN, 2=OPEN")
                .register(registry);
    }

    private Counter counter(String metric, String toolName, String outcome) {
        String key = metric + "|" + safe(toolName) + "|" + safe(outcome);
        return counters.computeIfAbsent(key, ignored -> Counter.builder(metric)
                .tag("tool", safe(toolName))
                .tag("outcome", safe(outcome))
                .register(registry));
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.length() <= 64 ? value : value.substring(0, 64);
    }
}
