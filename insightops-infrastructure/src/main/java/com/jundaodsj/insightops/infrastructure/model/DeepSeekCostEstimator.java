package com.jundaodsj.insightops.infrastructure.model;

import com.jundaodsj.insightops.infrastructure.config.DeepSeekPricingProperties;
import com.jundaodsj.insightops.model.application.ModelUsage;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

@Component
public class DeepSeekCostEstimator {

    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);

    // Standard text rates per million tokens. DeepSeek uses peak rates for a conservative estimate.
    // Reviewed 2026-11-20: developers.openai.com/api/docs/pricing and api-docs.deepseek.com/quick_start/pricing/
    private static final LocalDate RATES_REVIEWED = LocalDate.of(2026, 11, 20);
    private static final Map<String, Rates> RESEARCH_RATES = Map.of(
            "gpt-6.2-sol", Rates.of("0.10", "2", "10"),
            "gpt-6.2-astra", Rates.of("0.40", "8", "40"),
            "gpt-6.1-terra", Rates.of("0.05", "1", "5"),
            "gpt-6.1-sol", Rates.of("0.10", "2", "10"),
            "gpt-6-astra", Rates.of("1", "10", "50"),
            "gpt-6-sol", Rates.of("0.20", "2", "10"),
            "deepseek-flash", Rates.of("0.006", "0.30", "1.20"),
            "deepseek-v4-pro", Rates.of("0.044", "1.32", "3.96"));

    private final DeepSeekPricingProperties pricing;

    public DeepSeekCostEstimator(DeepSeekPricingProperties pricing) {
        this.pricing = pricing;
    }

    public Optional<CostEstimate> estimate(String provider, String model, ModelUsage usage) {
        if (model != null && ("openai".equals(provider) || "deepseek".equals(provider))) {
            var rates = RESEARCH_RATES.get(model.replaceFirst("-[0-9]{4}-[0-9]{2}-[0-9]{2}$", ""));
            if (rates != null) return estimate(usage, rates, RATES_REVIEWED);
        }
        return "openai".equals(provider) ? Optional.empty() : estimate(usage);
    }

    public Optional<CostEstimate> estimate(ModelUsage usage) {
        return estimate(usage, new Rates(pricing.inputCacheHitUsdPerMillion(), pricing.inputCacheMissUsdPerMillion(),
                pricing.outputUsdPerMillion()), pricing.effectiveDate());
    }

    private Optional<CostEstimate> estimate(ModelUsage usage, Rates rates, LocalDate effective) {
        if (usage == null || usage.inputTokens() == null || usage.outputTokens() == null) {
            return Optional.empty();
        }
        long inputTokens = Math.max(0, usage.inputTokens());
        long outputTokens = Math.max(0, usage.outputTokens());
        long cacheHitTokens = usage.cacheReadInputTokens() == null
                ? 0
                : Math.min(inputTokens, Math.max(0, usage.cacheReadInputTokens()));
        long cacheMissTokens = inputTokens - cacheHitTokens;

        BigDecimal usd = tokenCost(cacheHitTokens, rates.cached())
                .add(tokenCost(cacheMissTokens, rates.input()))
                .add(tokenCost(outputTokens, rates.output()));
        BigDecimal cny = usd.multiply(pricing.usdToCny()).setScale(6, RoundingMode.HALF_UP);
        return Optional.of(new CostEstimate(cny, effective));
    }

    private static BigDecimal tokenCost(long tokens, BigDecimal pricePerMillion) {
        return BigDecimal.valueOf(tokens).multiply(pricePerMillion)
                .divide(ONE_MILLION, 12, RoundingMode.HALF_UP);
    }

    private record Rates(BigDecimal cached, BigDecimal input, BigDecimal output) {
        static Rates of(String cached, String input, String output) {
            return new Rates(new BigDecimal(cached), new BigDecimal(input), new BigDecimal(output));
        }
    }

    public record CostEstimate(BigDecimal cny, LocalDate pricingEffectiveDate) {
    }
}
