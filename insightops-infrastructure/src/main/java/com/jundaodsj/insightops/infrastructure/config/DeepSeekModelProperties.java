package com.jundaodsj.insightops.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "insightops.model.deepseek")
public record DeepSeekModelProperties(
        boolean enabled,
        String baseUrl,
        String model,
        boolean thinkingEnabled,
        double temperature,
        int maxOutputTokens,
        int maxToolRounds,
        int requestTimeoutSeconds,
        int maxRetries,
        boolean smokeTestEnabled) {
}
