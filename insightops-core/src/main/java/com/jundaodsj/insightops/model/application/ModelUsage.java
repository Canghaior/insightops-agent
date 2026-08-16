package com.jundaodsj.insightops.model.application;

public record ModelUsage(
        Integer inputTokens,
        Integer outputTokens,
        Integer totalTokens,
        Long cacheReadInputTokens,
        Long cacheWriteInputTokens) {

    public static ModelUsage unknown() {
        return new ModelUsage(null, null, null, null, null);
    }
}
