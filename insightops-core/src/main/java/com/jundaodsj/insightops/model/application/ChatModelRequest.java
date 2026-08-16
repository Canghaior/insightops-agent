package com.jundaodsj.insightops.model.application;

public record ChatModelRequest(
        String systemPrompt,
        String userPrompt,
        double temperature,
        int maxOutputTokens) {

    public ChatModelRequest {
        systemPrompt = systemPrompt == null ? "" : systemPrompt.trim();
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("userPrompt must not be blank");
        }
        userPrompt = userPrompt.trim();
        if (temperature < 0.0 || temperature > 2.0) {
            throw new IllegalArgumentException("temperature must be between 0 and 2");
        }
        if (maxOutputTokens < 1 || maxOutputTokens > 8192) {
            throw new IllegalArgumentException("maxOutputTokens must be between 1 and 8192");
        }
    }
}
