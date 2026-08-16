package com.jundaodsj.insightops.infrastructure.model;

import com.jundaodsj.insightops.infrastructure.config.DeepSeekPricingProperties;
import com.jundaodsj.insightops.model.application.ModelUsage;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;

@Component
public class DeepSeekCostEstimator {

    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);

    private final DeepSeekPricingProperties pricing;

    public DeepSeekCostEstimator(DeepSeekPricingProperties pricing) {
        this.pricing = pricing;
    }

    public Optional<CostEstimate> estimate(ModelUsage usage) {
        if (usage == null || usage.inputTokens() == null || usage.outputTokens() == null) {
            return Optional.empty();
        }
        long inputTokens = Math.max(0, usage.inputTokens());
        long outputTokens = Math.max(0, usage.outputTokens());
        long cacheHitTokens = usage.cacheReadInputTokens() == null
                ? 0
                : Math.min(inputTokens, Math.max(0, usage.cacheReadInputTokens()));
        long cacheMissTokens = inputTokens - cacheHitTokens;

        BigDecimal usd = tokenCost(cacheHitTokens, pricing.inputCacheHitUsdPerMillion())
                .add(tokenCost(cacheMissTokens, pricing.inputCacheMissUsdPerMillion()))
                .add(tokenCost(outputTokens, pricing.outputUsdPerMillion()));
        BigDecimal cny = usd.multiply(pricing.usdToCny()).setScale(6, RoundingMode.HALF_UP);
        return Optional.of(new CostEstimate(cny, pricing.effectiveDate()));
    }

    private static BigDecimal tokenCost(long tokens, BigDecimal pricePerMillion) {
        return BigDecimal.valueOf(tokens).multiply(pricePerMillion)
                .divide(ONE_MILLION, 12, RoundingMode.HALF_UP);
    }

    public record CostEstimate(BigDecimal cny, LocalDate pricingEffectiveDate) {
    }
}
