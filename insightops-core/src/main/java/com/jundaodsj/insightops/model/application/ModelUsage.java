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

    public ModelUsage plus(ModelUsage other) {
        if (other == null) return this;
        return new ModelUsage(
                add(inputTokens, other.inputTokens),
                add(outputTokens, other.outputTokens),
                add(totalTokens, other.totalTokens),
                add(cacheReadInputTokens, other.cacheReadInputTokens),
                add(cacheWriteInputTokens, other.cacheWriteInputTokens));
    }

    private static Integer add(Integer left, Integer right) {
        if (left == null) return right;
        if (right == null) return left;
        return Math.addExact(left, right);
    }

    private static Long add(Long left, Long right) {
        if (left == null) return right;
        if (right == null) return left;
        return Math.addExact(left, right);
    }
}
