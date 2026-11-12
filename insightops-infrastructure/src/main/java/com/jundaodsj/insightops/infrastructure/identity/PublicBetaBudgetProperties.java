package com.jundaodsj.insightops.infrastructure.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** Applied only when creating a public workspace; existing administrator policies are retained. */
@Component
@ConfigurationProperties(prefix = "insightops.public-beta.workspace-budget")
public class PublicBetaBudgetProperties {
    private long dailyTokenLimit = 100_000;
    private long monthlyTokenLimit = 1_000_000;
    private BigDecimal dailyCostLimitCny = new BigDecimal("2.000000");
    private BigDecimal monthlyCostLimitCny = new BigDecimal("20.000000");

    public long getDailyTokenLimit() { return dailyTokenLimit; }
    public void setDailyTokenLimit(long value) { dailyTokenLimit = value; }
    public long getMonthlyTokenLimit() { return monthlyTokenLimit; }
    public void setMonthlyTokenLimit(long value) { monthlyTokenLimit = value; }
    public BigDecimal getDailyCostLimitCny() { return dailyCostLimitCny; }
    public void setDailyCostLimitCny(BigDecimal value) { dailyCostLimitCny = value; }
    public BigDecimal getMonthlyCostLimitCny() { return monthlyCostLimitCny; }
    public void setMonthlyCostLimitCny(BigDecimal value) { monthlyCostLimitCny = value; }

    public void validate() {
        if (dailyTokenLimit <= 0 || monthlyTokenLimit < dailyTokenLimit
                || dailyCostLimitCny == null || dailyCostLimitCny.signum() <= 0
                || monthlyCostLimitCny == null || monthlyCostLimitCny.compareTo(dailyCostLimitCny) < 0) {
            throw new IllegalArgumentException("Public workspace budgets must be positive; monthly limits must cover daily limits");
        }
    }
}
