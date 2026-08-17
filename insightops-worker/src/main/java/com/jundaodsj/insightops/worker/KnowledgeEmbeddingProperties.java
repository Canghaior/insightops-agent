package com.jundaodsj.insightops.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "insightops.knowledge.embedding")
public class KnowledgeEmbeddingProperties {
    private boolean enabled = false;
    private String provider = "ollama";
    private String model = "bge-m3";
    private int dimensions = 1024;
    private int batchSize = 32;
    private int maxBatchesPerCycle = 50;
    private int lockMinutes = 10;
    private int maxRetries = 3;
    private int retryMinutes = 2;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getDimensions() { return dimensions; }
    public void setDimensions(int dimensions) { this.dimensions = dimensions; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public int getMaxBatchesPerCycle() { return maxBatchesPerCycle; }
    public void setMaxBatchesPerCycle(int maxBatchesPerCycle) { this.maxBatchesPerCycle = maxBatchesPerCycle; }
    public int getLockMinutes() { return lockMinutes; }
    public void setLockMinutes(int lockMinutes) { this.lockMinutes = lockMinutes; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public int getRetryMinutes() { return retryMinutes; }
    public void setRetryMinutes(int retryMinutes) { this.retryMinutes = retryMinutes; }
}
