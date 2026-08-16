package com.jundaodsj.insightops.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.LocalDate;

@ConfigurationProperties(prefix = "insightops.model.deepseek.pricing")
public record DeepSeekPricingProperties(
        LocalDate effectiveDate,
        BigDecimal usdToCny,
        BigDecimal inputCacheHitUsdPerMillion,
        BigDecimal inputCacheMissUsdPerMillion,
        BigDecimal outputUsdPerMillion) {

    public DeepSeekPricingProperties {
        if (effectiveDate == null
                || !positive(usdToCny)
                || !nonNegative(inputCacheHitUsdPerMillion)
                || !nonNegative(inputCacheMissUsdPerMillion)
                || !nonNegative(outputUsdPerMillion)) {
            throw new IllegalArgumentException("DeepSeek pricing configuration is incomplete or invalid");
        }
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static boolean nonNegative(BigDecimal value) {
        return value != null && value.signum() >= 0;
    }
}
