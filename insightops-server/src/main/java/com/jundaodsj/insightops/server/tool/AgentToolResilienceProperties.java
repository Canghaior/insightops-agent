package com.jundaodsj.insightops.server.tool;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "insightops.tool.resilience")
public class AgentToolResilienceProperties {

    private boolean enabled = true;
    private int maxAttempts = 3;
    private int maxTotalAttempts = 8;
    private int runTimeoutSeconds = 90;
    private int maxToolDurationSeconds = 60;
    private long initialBackoffMs = 250;
    private long maxBackoffMs = 2_000;
    private long pollIntervalMs = 100;
    private int circuitWindowSize = 10;
    private int circuitMinimumCalls = 5;
    private int circuitFailureRatePercent = 50;
    private int circuitOpenSeconds = 30;
    private int circuitHalfOpenPermits = 2;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public int getMaxTotalAttempts() { return maxTotalAttempts; }
    public void setMaxTotalAttempts(int maxTotalAttempts) {
        this.maxTotalAttempts = maxTotalAttempts;
    }
    public int getRunTimeoutSeconds() { return runTimeoutSeconds; }
    public void setRunTimeoutSeconds(int runTimeoutSeconds) {
        this.runTimeoutSeconds = runTimeoutSeconds;
    }
    public int getMaxToolDurationSeconds() { return maxToolDurationSeconds; }
    public void setMaxToolDurationSeconds(int maxToolDurationSeconds) {
        this.maxToolDurationSeconds = maxToolDurationSeconds;
    }
    public long getInitialBackoffMs() { return initialBackoffMs; }
    public void setInitialBackoffMs(long initialBackoffMs) {
        this.initialBackoffMs = initialBackoffMs;
    }
    public long getMaxBackoffMs() { return maxBackoffMs; }
    public void setMaxBackoffMs(long maxBackoffMs) { this.maxBackoffMs = maxBackoffMs; }
    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
    public int getCircuitWindowSize() { return circuitWindowSize; }
    public void setCircuitWindowSize(int circuitWindowSize) {
        this.circuitWindowSize = circuitWindowSize;
    }
    public int getCircuitMinimumCalls() { return circuitMinimumCalls; }
    public void setCircuitMinimumCalls(int circuitMinimumCalls) {
        this.circuitMinimumCalls = circuitMinimumCalls;
    }
    public int getCircuitFailureRatePercent() { return circuitFailureRatePercent; }
    public void setCircuitFailureRatePercent(int circuitFailureRatePercent) {
        this.circuitFailureRatePercent = circuitFailureRatePercent;
    }
    public int getCircuitOpenSeconds() { return circuitOpenSeconds; }
    public void setCircuitOpenSeconds(int circuitOpenSeconds) {
        this.circuitOpenSeconds = circuitOpenSeconds;
    }
    public int getCircuitHalfOpenPermits() { return circuitHalfOpenPermits; }
    public void setCircuitHalfOpenPermits(int circuitHalfOpenPermits) {
        this.circuitHalfOpenPermits = circuitHalfOpenPermits;
    }
}
