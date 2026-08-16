package com.jundaodsj.insightops.infrastructure.model;

import com.jundaodsj.insightops.infrastructure.config.DeepSeekPricingProperties;
import com.jundaodsj.insightops.model.application.ModelUsage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class DeepSeekCostEstimatorTest {

    private final DeepSeekCostEstimator estimator = new DeepSeekCostEstimator(
            new DeepSeekPricingProperties(
                    LocalDate.parse("2026-08-16"),
                    new BigDecimal("7.20"),
                    new BigDecimal("0.0028"),
                    new BigDecimal("0.14"),
                    new BigDecimal("0.28")));

    @Test
    void shouldEstimateCacheHitMissAndOutputCost() {
        var estimate = estimator.estimate(new ModelUsage(1_000_000, 500_000, 1_500_000, 250_000L, 0L))
                .orElseThrow();

        assertThat(estimate.cny()).isEqualByComparingTo("1.769040");
        assertThat(estimate.pricingEffectiveDate()).isEqualTo(LocalDate.parse("2026-08-16"));
    }

    @Test
    void shouldUseConservativeCacheMissPriceWhenCacheUsageIsUnknown() {
        var estimate = estimator.estimate(new ModelUsage(1_000, 100, 1_100, null, null))
                .orElseThrow();

        assertThat(estimate.cny()).isEqualByComparingTo("0.001210");
        assertThat(estimator.estimate(ModelUsage.unknown())).isEmpty();
    }
}
