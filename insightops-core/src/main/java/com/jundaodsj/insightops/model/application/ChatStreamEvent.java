package com.jundaodsj.insightops.model.application;

import java.time.Duration;

public record ChatStreamEvent(
        ChatStreamEventType type,
        String content,
        String provider,
        String model,
        ModelUsage usage,
        Duration duration,
        Duration timeToFirstToken) {

    public ChatStreamEvent {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (type == ChatStreamEventType.CONTENT_DELTA && (content == null || content.isEmpty())) {
            throw new IllegalArgumentException("delta content must not be empty");
        }
        if (type == ChatStreamEventType.COMPLETED) {
            if (provider == null || provider.isBlank() || model == null || model.isBlank()) {
                throw new IllegalArgumentException("completed event requires provider and model");
            }
            if (duration == null || duration.isNegative()) {
                throw new IllegalArgumentException("completed event requires a non-negative duration");
            }
        }
        usage = usage == null ? ModelUsage.unknown() : usage;
    }

    public static ChatStreamEvent delta(String content) {
        return new ChatStreamEvent(
                ChatStreamEventType.CONTENT_DELTA,
                content,
                null,
                null,
                ModelUsage.unknown(),
                null,
                null);
    }

    public static ChatStreamEvent completed(
            String provider,
            String model,
            ModelUsage usage,
            Duration duration,
            Duration timeToFirstToken) {
        return new ChatStreamEvent(
                ChatStreamEventType.COMPLETED,
                null,
                provider,
                model,
                usage,
                duration,
                timeToFirstToken);
    }
}
