package com.jundaodsj.insightops.server.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "insightops.agent.chat-queue")
public class DurableChatRunProperties {

    private boolean enabled = true;
    private int concurrency = 2;
    private int pollIntervalMs = 500;
    private int initialDelayMs = 1_000;
    private int leaseSeconds = 30;
    private int heartbeatSeconds = 5;
    private int maxAttempts = 3;
    private int eventPollMs = 200;
    private int streamHeartbeatMs = 5_000;
    private int runTimeoutSeconds = 90;

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
    public int getEventPollMs() { return eventPollMs; }
    public void setEventPollMs(int eventPollMs) { this.eventPollMs = eventPollMs; }
    public int getStreamHeartbeatMs() { return streamHeartbeatMs; }
    public void setStreamHeartbeatMs(int streamHeartbeatMs) {
        this.streamHeartbeatMs = streamHeartbeatMs;
    }
    public int getRunTimeoutSeconds() { return runTimeoutSeconds; }
    public void setRunTimeoutSeconds(int runTimeoutSeconds) {
        this.runTimeoutSeconds = runTimeoutSeconds;
    }

    public int safeConcurrency() { return Math.max(1, Math.min(16, concurrency)); }
    public int safeMaxAttempts() { return Math.max(1, Math.min(20, maxAttempts)); }
    public Duration leaseDuration() { return Duration.ofSeconds(Math.max(30, leaseSeconds)); }
    public Duration heartbeatInterval() {
        long maximum = Math.max(1, leaseDuration().toSeconds() / 3);
        return Duration.ofSeconds(Math.max(1, Math.min(maximum, heartbeatSeconds)));
    }
    public Duration eventPollInterval() {
        return Duration.ofMillis(Math.max(50, Math.min(2_000, eventPollMs)));
    }
    public Duration streamHeartbeatInterval() {
        return Duration.ofMillis(Math.max(250, Math.min(30_000, streamHeartbeatMs)));
    }
    public Duration runTimeout() {
        return Duration.ofSeconds(Math.max(1, Math.min(900, runTimeoutSeconds)));
    }
}
