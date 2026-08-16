package com.jundaodsj.insightops.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("insightops.tool.github")
public record GitHubToolProperties(
        String baseUrl,
        String apiVersion,
        int connectTimeoutSeconds,
        int requestTimeoutSeconds,
        int maxBodyChars) {

    public GitHubToolProperties {
        baseUrl = normalize(baseUrl, "https://api.github.com");
        apiVersion = normalize(apiVersion, "2026-03-10");
        if (connectTimeoutSeconds < 1 || connectTimeoutSeconds > 30) {
            throw new IllegalArgumentException("connectTimeoutSeconds must be between 1 and 30");
        }
        if (requestTimeoutSeconds < 1 || requestTimeoutSeconds > 60) {
            throw new IllegalArgumentException("requestTimeoutSeconds must be between 1 and 60");
        }
        if (maxBodyChars < 200 || maxBodyChars > 4000) {
            throw new IllegalArgumentException("maxBodyChars must be between 200 and 4000");
        }
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.stripTrailing();
    }
}
