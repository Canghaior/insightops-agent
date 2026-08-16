package com.jundaodsj.insightops.model.application;

import java.time.Duration;

public record ChatModelResponse(
        String content,
        String provider,
        String model,
        ModelUsage usage,
        Duration duration) {

    public ChatModelResponse {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        content = content.trim();
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        usage = usage == null ? ModelUsage.unknown() : usage;
        if (duration == null || duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be null or negative");
        }
    }
}
