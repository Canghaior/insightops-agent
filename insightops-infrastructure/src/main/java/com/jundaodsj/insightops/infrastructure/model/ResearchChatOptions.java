package com.jundaodsj.insightops.infrastructure.model;

import com.jundaodsj.insightops.infrastructure.config.DeepSeekModelProperties;
import com.jundaodsj.insightops.model.application.ResearchModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.time.Duration;
import java.util.Map;

record ResearchChatOptions(DeepSeekModelProperties defaults, ResearchModel choice) {
    String provider() { return choice == null ? "deepseek" : choice.provider(); }
    String model(String override) {
        return choice != null ? choice.modelId() : override == null ? defaults.model() : override;
    }
    boolean thinking() { return choice == null ? defaults.thinkingEnabled() : choice.thinking(); }
    OpenAiChatOptions.Builder options(double temperature, int tokens, String override) {
        var options = OpenAiChatOptions.builder().model(model(override))
                .timeout(Duration.ofSeconds(defaults.requestTimeoutSeconds())).maxRetries(defaults.maxRetries());
        if ("openai".equals(provider())) return options.maxCompletionTokens(tokens);
        return options.temperature(temperature).maxTokens(tokens)
                .extraBody(Map.of("thinking", Map.of("type", thinking() ? "enabled" : "disabled")));
    }
}
