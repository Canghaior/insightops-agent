package com.jundaodsj.insightops.infrastructure.persistence;

import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.tool.application.McpConnectionStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcMcpConnectionStore implements McpConnectionStore {

    private final JdbcClient jdbcClient;

    public JdbcMcpConnectionStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<Connection> list(ActorContext actor) {
        return jdbcClient.sql("""
                select * from mcp_connection where workspace_id = :workspaceId
                order by enabled desc, name
                """)
                .param("workspaceId", actor.workspaceId())
                .query((rs, rowNum) -> connection(rs)).list();
    }

    @Override
    public Optional<Connection> find(ActorContext actor, UUID connectionId) {
        return jdbcClient.sql("""
                select * from mcp_connection
                where id = :id and workspace_id = :workspaceId
                """)
                .param("id", connectionId).param("workspaceId", actor.workspaceId())
                .query((rs, rowNum) -> connection(rs)).optional();
    }

    @Override
    public Connection create(ActorContext actor, CreateCommand command, Instant now) {
        jdbcClient.sql("""
                insert into mcp_connection
                    (id, workspace_id, name, endpoint, allowed_tools, enabled,
                     created_by, created_at, updated_at)
                values (:id, :workspaceId, :name, :endpoint, cast(:allowedTools as jsonb),
                        :enabled, :userId, :now, :now)
                """)
                .param("id", command.id()).param("workspaceId", actor.workspaceId())
                .param("name", command.name()).param("endpoint", command.endpoint())
                .param("allowedTools", command.allowedToolsJson()).param("enabled", command.enabled())
                .param("userId", actor.userId()).param("now", timestamp(now)).update();
        return find(actor, command.id()).orElseThrow();
    }

    @Override
    public Optional<Connection> update(
            ActorContext actor, UUID connectionId, UpdateCommand command, Instant now) {
        int updated = jdbcClient.sql("""
                update mcp_connection set name = :name, endpoint = :endpoint,
                    allowed_tools = cast(:allowedTools as jsonb), enabled = :enabled,
                    updated_at = :now
                where id = :id and workspace_id = :workspaceId
                """)
                .param("name", command.name()).param("endpoint", command.endpoint())
                .param("allowedTools", command.allowedToolsJson()).param("enabled", command.enabled())
                .param("now", timestamp(now)).param("id", connectionId)
                .param("workspaceId", actor.workspaceId()).update();
        return updated == 0 ? Optional.empty() : find(actor, connectionId);
    }

    @Override
    public boolean delete(ActorContext actor, UUID connectionId) {
        return jdbcClient.sql("""
                delete from mcp_connection where id = :id and workspace_id = :workspaceId
                """)
                .param("id", connectionId).param("workspaceId", actor.workspaceId())
                .update() == 1;
    }

    @Override
    public Optional<Connection> resolveEnabled(
            UUID workspaceId, UUID connectionId, String toolName) {
        return jdbcClient.sql("""
                select * from mcp_connection
                where id = :id and workspace_id = :workspaceId and enabled = true
                  and (allowed_tools -> :toolName) is not null
                """)
                .param("id", connectionId).param("workspaceId", workspaceId)
                .param("toolName", toolName)
                .query((rs, rowNum) -> connection(rs)).optional();
    }

    private static Connection connection(ResultSet rs) throws SQLException {
        return new Connection(
                rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("endpoint"), rs.getString("allowed_tools"),
                rs.getBoolean("enabled"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
