package com.jundaodsj.insightops.infrastructure.persistence;

import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.memory.application.UserMemoryStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcUserMemoryStore implements UserMemoryStore {

    private final JdbcClient jdbcClient;

    public JdbcUserMemoryStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<UserMemory> list(ActorContext actor) {
        return jdbcClient.sql("""
                select id, memory_key, memory_value, category, enabled, created_at, updated_at
                from user_memory
                where user_id = :userId and workspace_id = :workspaceId
                order by enabled desc, updated_at desc
                """)
                .param("userId", actor.userId())
                .param("workspaceId", actor.workspaceId())
                .query((resultSet, rowNum) -> memory(resultSet))
                .list();
    }

    @Override
    public Optional<UserMemory> find(ActorContext actor, UUID memoryId) {
        return jdbcClient.sql("""
                select id, memory_key, memory_value, category, enabled, created_at, updated_at
                from user_memory
                where id = :id and user_id = :userId and workspace_id = :workspaceId
                """)
                .param("id", memoryId)
                .param("userId", actor.userId())
                .param("workspaceId", actor.workspaceId())
                .query((resultSet, rowNum) -> memory(resultSet))
                .optional();
    }

    @Override
    public UserMemory create(
            ActorContext actor,
            UUID memoryId,
            String key,
            String value,
            String category,
            Instant now) {
        jdbcClient.sql("""
                insert into user_memory
                    (id, user_id, workspace_id, memory_key, memory_value, category,
                     enabled, created_at, updated_at)
                values
                    (:id, :userId, :workspaceId, :key, :value, :category,
                     true, :now, :now)
                """)
                .param("id", memoryId)
                .param("userId", actor.userId())
                .param("workspaceId", actor.workspaceId())
                .param("key", key)
                .param("value", value)
                .param("category", category)
                .param("now", timestamp(now))
                .update();
        return find(actor, memoryId).orElseThrow();
    }

    @Override
    public Optional<UserMemory> update(
            ActorContext actor,
            UUID memoryId,
            String value,
            String category,
            boolean enabled,
            Instant now) {
        int updated = jdbcClient.sql("""
                update user_memory
                set memory_value = :value,
                    category = :category,
                    enabled = :enabled,
                    updated_at = :now
                where id = :id and user_id = :userId and workspace_id = :workspaceId
                """)
                .param("value", value)
                .param("category", category)
                .param("enabled", enabled)
                .param("now", timestamp(now))
                .param("id", memoryId)
                .param("userId", actor.userId())
                .param("workspaceId", actor.workspaceId())
                .update();
        return updated == 0 ? Optional.empty() : find(actor, memoryId);
    }

    @Override
    public boolean delete(ActorContext actor, UUID memoryId) {
        return jdbcClient.sql("""
                delete from user_memory
                where id = :id and user_id = :userId and workspace_id = :workspaceId
                """)
                .param("id", memoryId)
                .param("userId", actor.userId())
                .param("workspaceId", actor.workspaceId())
                .update() == 1;
    }

    private static UserMemory memory(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new UserMemory(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("memory_key"),
                resultSet.getString("memory_value"),
                resultSet.getString("category"),
                resultSet.getBoolean("enabled"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
