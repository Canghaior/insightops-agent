package com.jundaodsj.insightops.infrastructure.persistence;

import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.project.application.UserProjectWatchStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcUserProjectWatchStore implements UserProjectWatchStore {
    private final JdbcClient jdbcClient;

    public JdbcUserProjectWatchStore(JdbcClient jdbcClient) { this.jdbcClient = jdbcClient; }

    @Override
    public List<ProjectWatch> list(ActorContext actor) {
        return jdbcClient.sql("""
                select project.id, project.repository_owner, project.repository_name,
                       project.canonical_url, project.priority,
                       coalesce(watch.enabled, false) as watched,
                       coalesce(watch.updated_at, project.updated_at) as updated_at
                from tracked_project project
                left join user_project_watch watch
                  on watch.project_id = project.id
                 and watch.user_id = :userId
                 and watch.workspace_id = :workspaceId
                where project.workspace_id = :workspaceId and project.enabled = true
                order by project.priority, project.repository_name
                """)
                .param("userId", actor.userId())
                .param("workspaceId", actor.workspaceId())
                .query((resultSet, rowNum) -> project(resultSet))
                .list();
    }

    @Override
    @Transactional
    public Optional<ProjectWatch> setEnabled(
            ActorContext actor, UUID projectId, boolean enabled, Instant now) {
        boolean exists = jdbcClient.sql("""
                select count(*) = 1 from tracked_project
                where id = :projectId and workspace_id = :workspaceId and enabled = true
                """)
                .param("projectId", projectId)
                .param("workspaceId", actor.workspaceId())
                .query(Boolean.class).single();
        if (!exists) return Optional.empty();
        jdbcClient.sql("""
                insert into user_project_watch
                    (user_id, workspace_id, project_id, enabled, created_at, updated_at)
                values (:userId, :workspaceId, :projectId, :enabled, :now, :now)
                on conflict (user_id, project_id) do update
                set enabled = excluded.enabled, updated_at = excluded.updated_at
                """)
                .param("userId", actor.userId())
                .param("workspaceId", actor.workspaceId())
                .param("projectId", projectId)
                .param("enabled", enabled)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
        return list(actor).stream().filter(project -> project.id().equals(projectId)).findFirst();
    }

    private static ProjectWatch project(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new ProjectWatch(
                resultSet.getObject("id", UUID.class), resultSet.getString("repository_owner"),
                resultSet.getString("repository_name"), resultSet.getString("canonical_url"),
                resultSet.getInt("priority"), resultSet.getBoolean("watched"),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
