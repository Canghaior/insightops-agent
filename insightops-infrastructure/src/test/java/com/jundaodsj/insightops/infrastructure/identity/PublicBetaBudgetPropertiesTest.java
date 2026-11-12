package com.jundaodsj.insightops.infrastructure.identity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class PublicBetaBudgetPropertiesTest {
    @Test
    void bindsFiniteWorkspaceBudgetsAndAllowsExplicitOverrides() {
        new ApplicationContextRunner().withUserConfiguration(BudgetConfiguration.class)
                .withPropertyValues("insightops.public-beta.workspace-budget.daily-token-limit=200000",
                        "insightops.public-beta.workspace-budget.daily-cost-limit-cny=3.5")
                .run(context -> {
                    var budget = context.getBean(PublicBetaBudgetProperties.class);
                    budget.validate();
                    assertThat(budget.getDailyTokenLimit()).isEqualTo(200_000);
                    assertThat(budget.getDailyCostLimitCny()).isEqualByComparingTo("3.5");
                    assertThat(budget.getMonthlyTokenLimit()).isEqualTo(1_000_000);
                    assertThat(budget.getMonthlyCostLimitCny()).isEqualByComparingTo("20");
                });
    }

    @Test
    void rejectsInvalidBudgetsBeforeRegistrationCanWriteAnything() {
        var budget = new PublicBetaBudgetProperties();
        budget.setDailyTokenLimit(0);
        assertThatThrownBy(() -> new PublicBetaRepository(mock(JdbcClient.class), budget))
                .isInstanceOf(IllegalArgumentException.class);
        budget.setDailyTokenLimit(100_000);
        budget.setMonthlyCostLimitCny(new BigDecimal("0.50"));
        assertThatThrownBy(budget::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PublicBetaBudgetProperties.class)
    static class BudgetConfiguration { }
}
