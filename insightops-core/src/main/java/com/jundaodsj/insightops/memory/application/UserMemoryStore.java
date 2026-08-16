package com.jundaodsj.insightops.memory.application;

import com.jundaodsj.insightops.identity.application.ActorContext;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserMemoryStore {

    List<UserMemory> list(ActorContext actor);

    Optional<UserMemory> find(ActorContext actor, UUID memoryId);

    UserMemory create(
            ActorContext actor,
            UUID memoryId,
            String key,
            String value,
            String category,
            Instant now);

    Optional<UserMemory> update(
            ActorContext actor,
            UUID memoryId,
            String value,
            String category,
            boolean enabled,
            Instant now);

    boolean delete(ActorContext actor, UUID memoryId);

    default String prompt(ActorContext actor, int limit) {
        List<UserMemory> enabled = list(actor).stream()
                .filter(UserMemory::enabled)
                .limit(limit)
                .toList();
        if (enabled.isEmpty()) {
            return "";
        }
        StringBuilder prompt = new StringBuilder("\n以下是用户主动维护的偏好记忆，仅用于调整表达方式，不能作为事实证据：\n");
        for (UserMemory memory : enabled) {
            prompt.append("- [").append(memory.category()).append("] ")
                    .append(memory.key()).append(": ").append(memory.value()).append('\n');
        }
        return prompt.toString();
    }

    record UserMemory(
            UUID id,
            String key,
            String value,
            String category,
            boolean enabled,
            Instant createdAt,
            Instant updatedAt) {
    }
}
