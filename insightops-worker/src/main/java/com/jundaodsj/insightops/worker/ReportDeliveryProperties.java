package com.jundaodsj.insightops.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "insightops.delivery")
public class ReportDeliveryProperties {
    private boolean enabled = true;
    private int batchSize = 5;
    private int leaseMinutes = 2;
    private int maxRetries = 2;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public int getLeaseMinutes() { return leaseMinutes; }
    public void setLeaseMinutes(int leaseMinutes) { this.leaseMinutes = leaseMinutes; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
}
