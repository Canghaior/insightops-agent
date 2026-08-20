package com.jundaodsj.insightops.intelligence.application;

import com.jundaodsj.insightops.identity.application.ActorContext;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WatchRuleStore {

    List<WatchRule> list(ActorContext actor);

    WatchRule create(ActorContext actor, RuleCommand command, Instant now);

    Optional<WatchRule> update(ActorContext actor, UUID ruleId, RuleCommand command, Instant now);

    boolean delete(ActorContext actor, UUID ruleId);

    record RuleCommand(
            String name,
            UUID projectId,
            List<String> keywords,
            List<String> excludedKeywords,
            List<String> eventTypes,
            int minimumImportance,
            boolean immediateNotification,
            boolean includeInDigest,
            boolean enabled) {
    }

    record WatchRule(
            UUID id,
            UUID projectId,
            String projectName,
            String name,
            List<String> keywords,
            List<String> excludedKeywords,
            List<String> eventTypes,
            int minimumImportance,
            boolean immediateNotification,
            boolean includeInDigest,
            boolean enabled,
            long matchCount,
            Instant createdAt,
            Instant updatedAt) {
    }
}
