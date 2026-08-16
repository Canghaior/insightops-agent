package com.jundaodsj.insightops.infrastructure.config;

public record ModelReadiness(
        boolean enabled,
        boolean apiKeyConfigured,
        String provider,
        String model) {

    public boolean ready() {
        return enabled && apiKeyConfigured;
    }
}
