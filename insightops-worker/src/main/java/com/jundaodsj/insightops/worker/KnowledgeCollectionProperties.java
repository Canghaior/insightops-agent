package com.jundaodsj.insightops.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "insightops.knowledge.collection")
public class KnowledgeCollectionProperties {
    private boolean enabled = false;
    private int batchSize = 1;
    private int syncIntervalHours = 24;
    private int lockMinutes = 10;
    private int maxPagesPerSource = 200;
    private int maxDepth = 4;
    private int maxBytesPerPage = 2_097_152;
    private int requestTimeoutSeconds = 20;
    private int requestDelayMs = 500;
    private int chunkMaxTokens = 600;
    private int chunkOverlapTokens = 80;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public int getSyncIntervalHours() { return syncIntervalHours; }
    public void setSyncIntervalHours(int syncIntervalHours) { this.syncIntervalHours = syncIntervalHours; }
    public int getLockMinutes() { return lockMinutes; }
    public void setLockMinutes(int lockMinutes) { this.lockMinutes = lockMinutes; }
    public int getMaxPagesPerSource() { return maxPagesPerSource; }
    public void setMaxPagesPerSource(int maxPagesPerSource) { this.maxPagesPerSource = maxPagesPerSource; }
    public int getMaxDepth() { return maxDepth; }
    public void setMaxDepth(int maxDepth) { this.maxDepth = maxDepth; }
    public int getMaxBytesPerPage() { return maxBytesPerPage; }
    public void setMaxBytesPerPage(int maxBytesPerPage) { this.maxBytesPerPage = maxBytesPerPage; }
    public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) { this.requestTimeoutSeconds = requestTimeoutSeconds; }
    public int getRequestDelayMs() { return requestDelayMs; }
    public void setRequestDelayMs(int requestDelayMs) { this.requestDelayMs = requestDelayMs; }
    public int getChunkMaxTokens() { return chunkMaxTokens; }
    public void setChunkMaxTokens(int chunkMaxTokens) { this.chunkMaxTokens = chunkMaxTokens; }
    public int getChunkOverlapTokens() { return chunkOverlapTokens; }
    public void setChunkOverlapTokens(int chunkOverlapTokens) { this.chunkOverlapTokens = chunkOverlapTokens; }
}
