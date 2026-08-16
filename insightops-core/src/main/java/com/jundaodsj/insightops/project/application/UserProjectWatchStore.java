package com.jundaodsj.insightops.project.application;

import com.jundaodsj.insightops.identity.application.ActorContext;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserProjectWatchStore {
    List<ProjectWatch> list(ActorContext actor);
    Optional<ProjectWatch> setEnabled(ActorContext actor, UUID projectId, boolean enabled, Instant now);

    record ProjectWatch(
            UUID id, String owner, String name, String url,
            int priority, boolean enabled, Instant updatedAt) {
    }
}
