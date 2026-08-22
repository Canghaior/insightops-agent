package com.jundaodsj.insightops.server.evaluation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "insightops.agent.evaluation-queue")
public class AgentEvaluationQueueProperties {

    private boolean enabled = true;
    private int concurrency = 1;
    private int pollIntervalMs = 2_000;
    private int initialDelayMs = 1_000;
    private int leaseSeconds = 180;
    private int heartbeatSeconds = 30;
    private int maxAttempts = 3;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getConcurrency() { return concurrency; }
    public void setConcurrency(int concurrency) { this.concurrency = concurrency; }
    public int getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(int pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
    public int getInitialDelayMs() { return initialDelayMs; }
    public void setInitialDelayMs(int initialDelayMs) { this.initialDelayMs = initialDelayMs; }
    public int getLeaseSeconds() { return leaseSeconds; }
    public void setLeaseSeconds(int leaseSeconds) { this.leaseSeconds = leaseSeconds; }
    public int getHeartbeatSeconds() { return heartbeatSeconds; }
    public void setHeartbeatSeconds(int heartbeatSeconds) { this.heartbeatSeconds = heartbeatSeconds; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

    int safeConcurrency() { return Math.max(1, Math.min(8, concurrency)); }
    int safeMaxAttempts() { return Math.max(1, Math.min(20, maxAttempts)); }
    Duration leaseDuration() { return Duration.ofSeconds(Math.max(30, leaseSeconds)); }
    Duration heartbeatInterval() {
        long maximum = Math.max(1, leaseDuration().toSeconds() / 3);
        return Duration.ofSeconds(Math.max(1, Math.min(maximum, heartbeatSeconds)));
    }
}
