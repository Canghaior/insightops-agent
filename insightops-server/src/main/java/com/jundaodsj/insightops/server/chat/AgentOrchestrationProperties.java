package com.jundaodsj.insightops.server.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@ConfigurationProperties(prefix = "insightops.agent.orchestration")
public class AgentOrchestrationProperties {

    private boolean enabled = true;
    private int maxNodes = 12;
    private int maxParallelism = 3;
    private long maxModelTokens = 16_000;
    private BigDecimal maxEstimatedCostCny = new BigDecimal("0.500000");

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxNodes() { return maxNodes; }
    public void setMaxNodes(int maxNodes) { this.maxNodes = maxNodes; }
    public int getMaxParallelism() { return maxParallelism; }
    public void setMaxParallelism(int maxParallelism) { this.maxParallelism = maxParallelism; }
    public long getMaxModelTokens() { return maxModelTokens; }
    public void setMaxModelTokens(long maxModelTokens) { this.maxModelTokens = maxModelTokens; }
    public BigDecimal getMaxEstimatedCostCny() { return maxEstimatedCostCny; }
    public void setMaxEstimatedCostCny(BigDecimal maxEstimatedCostCny) {
        this.maxEstimatedCostCny = maxEstimatedCostCny;
    }
}
