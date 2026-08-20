package com.jundaodsj.insightops.infrastructure.persistence;

import com.jundaodsj.insightops.project.application.AdminProjectStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcAdminProjectStore implements AdminProjectStore {

    private static final String SELECT_PROJECT = """
            select project.id, project.platform, project.repository_owner, project.repository_name,
                   project.canonical_url, project.priority, project.enabled,
                   project.sync_interval_hours, project.chat_aliases,
                   project.last_sync_status, project.last_sync_at, project.next_sync_at,
                   project.consecutive_failures, project.last_sync_error,
                   (select count(*) from source_snapshot snapshot
                    where snapshot.project_id=project.id) as release_count,
                   (select count(*) from knowledge_source source
                    where source.project_id=project.id) as knowledge_source_count,
                   (select count(*) from user_project_watch watch
                    where watch.project_id=project.id) as watcher_count,
                   (select count(*) from job_task job
                    where job.project_id=project.id
                      and job.status in ('PENDING', 'RUNNING', 'RETRY_WAIT')) as active_job_count,
                   project.created_at, project.updated_at
            from tracked_project project
            """;

    private final JdbcClient jdbcClient;

    public JdbcAdminProjectStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<ManagedProject> list(UUID workspaceId) {
        return jdbcClient.sql(SELECT_PROJECT + """
                where project.workspace_id=:workspaceId
                order by project.enabled desc, project.priority, project.repository_name
                """)
                .param("workspaceId", workspaceId)
                .query(JdbcAdminProjectStore::project)
                .list();
    }

    @Override
    public Optional<ManagedProject> find(UUID workspaceId, UUID projectId) {
        return jdbcClient.sql(SELECT_PROJECT + """
                where project.workspace_id=:workspaceId and project.id=:projectId
                """)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .query(JdbcAdminProjectStore::project)
                .optional();
    }

    @Override
    @Transactional
    public ManagedProject create(
            UUID projectId,
            UUID workspaceId,
            String repositoryOwner,
            String repositoryName,
            String canonicalUrl,
            int priority,
            int syncIntervalHours,
            List<String> chatAliases,
            Instant now) {
        jdbcClient.sql("""
                insert into tracked_project
                    (id, workspace_id, platform, repository_owner, repository_name,
                     canonical_url, priority, sync_interval_hours, chat_aliases,
                     enabled, next_sync_at, created_at, updated_at)
                values (:projectId, :workspaceId, 'github', :owner, :repository,
                        :canonicalUrl, :priority, :syncIntervalHours, :chatAliases,
                        true, :now, :now, :now)
                """)
                .param("projectId", projectId)
                .param("workspaceId", workspaceId)
                .param("owner", repositoryOwner)
                .param("repository", repositoryName)
                .param("canonicalUrl", canonicalUrl)
                .param("priority", priority)
                .param("syncIntervalHours", syncIntervalHours)
                .param("chatAliases", chatAliases.toArray(String[]::new))
                .param("now", timestamp(now))
                .update();
        return find(workspaceId, projectId).orElseThrow();
    }

    @Override
    @Transactional
    public Optional<ManagedProject> update(
            UUID workspaceId,
            UUID projectId,
            String repositoryOwner,
            String repositoryName,
            String canonicalUrl,
            int priority,
            int syncIntervalHours,
            List<String> chatAliases,
            Instant now) {
        int updated = jdbcClient.sql("""
                update tracked_project
                set repository_owner=:owner, repository_name=:repository,
                    canonical_url=:canonicalUrl, priority=:priority,
                    sync_interval_hours=:syncIntervalHours, chat_aliases=:chatAliases,
                    updated_at=:now
                where workspace_id=:workspaceId and id=:projectId
                """)
                .param("owner", repositoryOwner)
                .param("repository", repositoryName)
                .param("canonicalUrl", canonicalUrl)
                .param("priority", priority)
                .param("syncIntervalHours", syncIntervalHours)
                .param("chatAliases", chatAliases.toArray(String[]::new))
                .param("now", timestamp(now))
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .update();
        return updated == 1 ? find(workspaceId, projectId) : Optional.empty();
    }

    @Override
    @Transactional
    public Optional<ManagedProject> setEnabled(
            UUID workspaceId, UUID projectId, boolean enabled, Instant now) {
        int updated = jdbcClient.sql("""
                update tracked_project
                set enabled=:enabled,
                    next_sync_at=case when :enabled then :now else next_sync_at end,
                    sync_locked_until=case when :enabled then sync_locked_until else null end,
                    last_sync_status=case when :enabled then last_sync_status
                                          when last_sync_status='RUNNING' then 'NEVER'
                                          else last_sync_status end,
                    updated_at=:now
                where workspace_id=:workspaceId and id=:projectId
                """)
                .param("enabled", enabled)
                .param("now", timestamp(now))
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .update();
        return updated == 1 ? find(workspaceId, projectId) : Optional.empty();
    }

    @Override
    @Transactional
    public DeleteResult deleteEmpty(UUID workspaceId, UUID projectId) {
        Optional<UUID> lockedProject = jdbcClient.sql("""
                select id from tracked_project
                where workspace_id=:workspaceId and id=:projectId
                for update
                """)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .query(UUID.class)
                .optional();
        if (lockedProject.isEmpty()) return DeleteResult.NOT_FOUND;
        boolean hasDependencies = jdbcClient.sql("""
                select exists (select 1 from source_snapshot where project_id=:projectId)
                    or exists (select 1 from knowledge_source where project_id=:projectId)
                    or exists (select 1 from user_project_watch where project_id=:projectId)
                    or exists (
                        select 1 from job_task
                        where project_id=:projectId
                          and status in ('PENDING', 'RUNNING', 'RETRY_WAIT'))
                """)
                .param("projectId", projectId)
                .query(Boolean.class)
                .single();
        if (hasDependencies) return DeleteResult.HAS_DEPENDENCIES;
        jdbcClient.sql("""
                delete from job_task
                where project_id=:projectId
                  and status in ('SUCCEEDED', 'FAILED', 'DEAD_LETTER')
                """)
                .param("projectId", projectId)
                .update();
        jdbcClient.sql("delete from tracked_project where id=:projectId")
                .param("projectId", projectId)
                .update();
        return DeleteResult.DELETED;
    }

    private static ManagedProject project(ResultSet resultSet, int rowNum) throws SQLException {
        return new ManagedProject(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("platform"),
                resultSet.getString("repository_owner"),
                resultSet.getString("repository_name"),
                resultSet.getString("canonical_url"),
                resultSet.getInt("priority"),
                resultSet.getInt("sync_interval_hours"),
                stringArray(resultSet, "chat_aliases"),
                resultSet.getBoolean("enabled"),
                resultSet.getString("last_sync_status"),
                instant(resultSet, "last_sync_at"),
                instant(resultSet, "next_sync_at"),
                resultSet.getInt("consecutive_failures"),
                resultSet.getString("last_sync_error"),
                resultSet.getLong("release_count"),
                resultSet.getLong("knowledge_source_count"),
                resultSet.getLong("watcher_count"),
                resultSet.getLong("active_job_count"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"));
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static List<String> stringArray(ResultSet resultSet, String column) throws SQLException {
        java.sql.Array value = resultSet.getArray(column);
        return value == null ? List.of() : List.of((String[]) value.getArray());
    }
}
