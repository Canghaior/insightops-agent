package com.jundaodsj.insightops.infrastructure.config;

public record ModelReadiness(
        boolean enabled,
        boolean apiKeyConfigured,
        String provider,
        String model) {

    public boolean ready() {
        return enabled && apiKeyConfigured;
    }

    /** Configuration only: this does not probe the provider or certify a successful request. */
    public String configurationState() {
        if (!enabled) return "DISABLED";
        return apiKeyConfigured ? "CONFIGURED_UNVERIFIED" : "MISSING_CONFIGURATION";
    }
}
