package com.jundaodsj.insightops.businesssystem.application;

import com.jundaodsj.insightops.identity.application.ActorContext;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BusinessSystemStore {
    record Background(String name, String purpose, String keyProcesses, String technologyStack,
                      String currentProblems, String constraints) { }
    record Profile(UUID id, int revision, Background background, List<UUID> uploadIds, Instant updatedAt) { }
    record Summary(UUID id, String name, String purpose, int revision, Instant updatedAt) { }
    record Run(UUID runId, String projectName, String currentVersion, String targetVersion,
               int systemRevision, String status, Instant createdAt, AssessmentType assessmentType, String question) { }

    List<Summary> list(ActorContext actor);
    Optional<Profile> find(ActorContext actor, UUID id);
    UUID create(ActorContext actor, Background background, List<UUID> uploadIds);
    boolean update(ActorContext actor, UUID id, int expectedRevision, Background background, List<UUID> uploadIds);
    List<Run> runs(ActorContext actor, UUID id, int limit, long offset);
}
