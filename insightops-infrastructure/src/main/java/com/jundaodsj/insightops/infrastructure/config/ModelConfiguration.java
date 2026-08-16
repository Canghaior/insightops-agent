package com.jundaodsj.insightops.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        DeepSeekModelProperties.class,
        DeepSeekPricingProperties.class,
        GitHubToolProperties.class
})
public class ModelConfiguration {

    @Bean
    ModelReadiness modelReadiness(
            DeepSeekModelProperties properties,
            @Value("${spring.ai.openai.api-key:}") String apiKey) {
        return new ModelReadiness(
                properties.enabled(),
                !apiKey.isBlank(),
                "deepseek",
                properties.model());
    }
}
