package com.jundaodsj.insightops.tool.application.github;

import java.time.Instant;
import java.util.List;

public interface GitHubProjectEventGateway {

    FetchResult fetch(String repositoryOwner, String repositoryName, int maxPerSource);

    record FetchResult(
            List<GitHubProjectEvent> events,
            List<String> unavailableSources,
            Instant fetchedAt) {
        public FetchResult {
            events = events == null ? List.of() : List.copyOf(events);
            unavailableSources = unavailableSources == null ? List.of() : List.copyOf(unavailableSources);
        }
    }
}
