package com.jundaodsj.insightops.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "insightops.collection")
public class ReleaseCollectionProperties {
    private boolean enabled = true;
    private int batchSize = 3;
    private int syncIntervalHours = 6;
    private int lockMinutes = 5;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public int getSyncIntervalHours() { return syncIntervalHours; }
    public void setSyncIntervalHours(int syncIntervalHours) { this.syncIntervalHours = syncIntervalHours; }
    public int getLockMinutes() { return lockMinutes; }
    public void setLockMinutes(int lockMinutes) { this.lockMinutes = lockMinutes; }
}
