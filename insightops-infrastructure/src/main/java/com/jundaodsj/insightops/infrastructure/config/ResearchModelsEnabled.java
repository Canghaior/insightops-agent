package com.jundaodsj.insightops.infrastructure.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class ResearchModelsEnabled implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var env = context.getEnvironment();
        return env.getProperty("insightops.model.deepseek.enabled", Boolean.class, false)
                || !env.getProperty("insightops.model.openai.api-key", "").isBlank();
    }
}
