package com.jundaodsj.insightops.tool.application;

import com.jundaodsj.insightops.identity.application.ActorContext;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Workspace-scoped allowlist for read-only MCP tools. */
public interface McpConnectionStore {
    List<Connection> list(ActorContext actor);
    Optional<Connection> find(ActorContext actor, UUID connectionId);
    Connection create(ActorContext actor, CreateCommand command, Instant now);
    Optional<Connection> update(ActorContext actor, UUID connectionId, UpdateCommand command, Instant now);
    boolean delete(ActorContext actor, UUID connectionId);
    Optional<Connection> resolveEnabled(UUID workspaceId, UUID connectionId, String toolName);

    record CreateCommand(UUID id, String name, String endpoint, String allowedToolsJson, boolean enabled) {}
    record UpdateCommand(String name, String endpoint, String allowedToolsJson, boolean enabled) {}
    record Connection(UUID id, String name, String endpoint, String allowedToolsJson,
            boolean enabled, Instant createdAt, Instant updatedAt) {}
}
