package com.jundaodsj.insightops.server.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@ConfigurationProperties(prefix = "insightops.agent.cost-governance")
public class AgentCostGovernanceProperties {

    private boolean enabled = true;
    private long dailyTokenLimit = 500_000;
    private BigDecimal dailyCostLimitCny = new BigDecimal("20.000000");
    private long monthlyTokenLimit = 10_000_000;
    private BigDecimal monthlyCostLimitCny = new BigDecimal("300.000000");
    private int maxConcurrentRuns = 5;
    private int warningPercent = 80;
    private boolean hardLimitEnabled = true;
    private String timezone = "Asia/Shanghai";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getDailyTokenLimit() { return dailyTokenLimit; }
    public void setDailyTokenLimit(long value) { dailyTokenLimit = value; }
    public BigDecimal getDailyCostLimitCny() { return dailyCostLimitCny; }
    public void setDailyCostLimitCny(BigDecimal value) { dailyCostLimitCny = value; }
    public long getMonthlyTokenLimit() { return monthlyTokenLimit; }
    public void setMonthlyTokenLimit(long value) { monthlyTokenLimit = value; }
    public BigDecimal getMonthlyCostLimitCny() { return monthlyCostLimitCny; }
    public void setMonthlyCostLimitCny(BigDecimal value) { monthlyCostLimitCny = value; }
    public int getMaxConcurrentRuns() { return maxConcurrentRuns; }
    public void setMaxConcurrentRuns(int value) { maxConcurrentRuns = value; }
    public int getWarningPercent() { return warningPercent; }
    public void setWarningPercent(int value) { warningPercent = value; }
    public boolean isHardLimitEnabled() { return hardLimitEnabled; }
    public void setHardLimitEnabled(boolean value) { hardLimitEnabled = value; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String value) { timezone = value; }
}
