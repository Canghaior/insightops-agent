package com.jundaodsj.insightops.tool.application.github;

import java.time.Instant;
import java.util.List;

public record GitHubProjectEvent(
        String externalId,
        String eventType,
        String title,
        String summary,
        String sourceUrl,
        String state,
        String authorLogin,
        List<String> labels,
        String riskLevel,
        int importance,
        Instant occurredAt,
        Instant updatedAt,
        String rawJson) {

    public GitHubProjectEvent {
        labels = labels == null ? List.of() : List.copyOf(labels);
        importance = Math.max(1, Math.min(importance, 5));
    }
}
