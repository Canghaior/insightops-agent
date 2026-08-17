package com.jundaodsj.insightops.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.project.application.ProjectUpdateStore;
import com.jundaodsj.insightops.tool.application.github.GitHubRelease;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcProjectUpdateStore implements ProjectUpdateStore {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public JdbcProjectUpdateStore(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public List<TrackedProject> claimDueProjects(Instant now, Duration lockDuration, int limit) {
        return jdbcClient.sql("""
                with due as (
                    select project.id
                    from tracked_project project
                    where project.enabled = true
                      and coalesce(project.next_sync_at, :now) <= :now
                      and (project.sync_locked_until is null or project.sync_locked_until <= :now)
                      and exists (
                          select 1 from user_project_watch watch
                          where watch.project_id = project.id and watch.enabled = true)
                    order by coalesce(project.next_sync_at, project.created_at), project.priority
                    for update skip locked
                    limit :limit
                )
                update tracked_project project
                set sync_locked_until = :lockUntil,
                    last_sync_status = 'RUNNING',
                    last_sync_error = null,
                    updated_at = :now
                from due
                where project.id = due.id
                returning project.id, project.workspace_id, project.repository_name,
                          project.repository_owner, project.consecutive_failures
                """)
                .param("now", timestamp(now))
                .param("lockUntil", timestamp(now.plus(lockDuration)))
                .param("limit", Math.max(1, Math.min(limit, 20)))
                .query((resultSet, rowNum) -> new TrackedProject(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("workspace_id", UUID.class),
                        resultSet.getString("repository_name"),
                        resultSet.getString("repository_owner"),
                        resultSet.getString("repository_name"),
                        resultSet.getInt("consecutive_failures")))
                .list();
    }

    @Override
    @Transactional
    public SyncResult completeSuccessfulSync(
            TrackedProject project,
            List<GitHubRelease> releases,
            Instant fetchedAt,
            Instant nextSyncAt) {
        int newEvents = 0;
        for (GitHubRelease release : releases) {
            if (!project.catalogProjectId().equals(release.projectId())) continue;
            Optional<UUID> existingSnapshot = jdbcClient.sql("""
                    select id from source_snapshot
                    where project_id = :projectId
                      and source_type = 'GITHUB_RELEASE'
                      and external_id = :externalId
                    """)
                    .param("projectId", project.id())
                    .param("externalId", release.tagName())
                    .query(UUID.class)
                    .optional();
            UUID snapshotId = existingSnapshot.orElseGet(UUID::randomUUID);
            String raw = releaseJson(release);
            if (existingSnapshot.isEmpty()) {
                jdbcClient.sql("""
                        insert into source_snapshot
                            (id, project_id, source_type, external_id, version_tag, source_url,
                             content_sha256, raw_content, published_at, collected_at)
                        values (:id, :projectId, 'GITHUB_RELEASE', :externalId, :versionTag, :sourceUrl,
                                :contentHash, cast(:raw as jsonb), :publishedAt, :collectedAt)
                        """)
                        .param("id", snapshotId)
                        .param("projectId", project.id())
                        .param("externalId", release.tagName())
                        .param("versionTag", release.tagName())
                        .param("sourceUrl", release.url())
                        .param("contentHash", sha256(raw))
                        .param("raw", raw)
                        .param("publishedAt", timestamp(release.publishedAt()))
                        .param("collectedAt", timestamp(fetchedAt))
                        .update();
                UUID eventId = UUID.randomUUID();
                jdbcClient.sql("""
                        insert into intelligence_event
                            (id, project_id, snapshot_id, event_type, title, summary,
                             importance, occurred_at, payload, analysis_eligible, created_at)
                        values (:id, :projectId, :snapshotId, 'GITHUB_RELEASE', :title, :summary,
                                :importance, :occurredAt, cast(:payload as jsonb), true, :createdAt)
                        """)
                        .param("id", eventId)
                        .param("projectId", project.id())
                        .param("snapshotId", snapshotId)
                        .param("title", release.releaseName())
                        .param("summary", summary(release))
                        .param("importance", release.prerelease() ? 2 : 3)
                        .param("occurredAt", timestamp(release.publishedAt()))
                        .param("payload", raw)
                        .param("createdAt", timestamp(fetchedAt))
                        .update();
                jdbcClient.sql("""
                        insert into event_evidence
                            (id, event_id, snapshot_id, source_url, evidence_text, sort_order, created_at)
                        values (:id, :eventId, :snapshotId, :sourceUrl, :evidence, 0, :createdAt)
                        """)
                        .param("id", UUID.randomUUID())
                        .param("eventId", eventId)
                        .param("snapshotId", snapshotId)
                        .param("sourceUrl", release.url())
                        .param("evidence", summary(release))
                        .param("createdAt", timestamp(fetchedAt))
                        .update();
                newEvents++;
            } else {
                jdbcClient.sql("""
                        update source_snapshot
                        set version_tag = :versionTag, source_url = :sourceUrl,
                            content_sha256 = :contentHash, raw_content = cast(:raw as jsonb),
                            published_at = :publishedAt, collected_at = :collectedAt
                        where id = :id
                        """)
                        .param("versionTag", release.tagName())
                        .param("sourceUrl", release.url())
                        .param("contentHash", sha256(raw))
                        .param("raw", raw)
                        .param("publishedAt", timestamp(release.publishedAt()))
                        .param("collectedAt", timestamp(fetchedAt))
                        .param("id", snapshotId)
                        .update();
                jdbcClient.sql("""
                        update intelligence_event
                        set title = :title, summary = :summary, occurred_at = :occurredAt,
                            payload = cast(:payload as jsonb)
                        where snapshot_id = :snapshotId
                        """)
                        .param("title", release.releaseName())
                        .param("summary", summary(release))
                        .param("occurredAt", timestamp(release.publishedAt()))
                        .param("payload", raw)
                        .param("snapshotId", snapshotId)
                        .update();
                jdbcClient.sql("""
                        update event_evidence
                        set source_url = :sourceUrl, evidence_text = :evidence
                        where snapshot_id = :snapshotId
                        """)
                        .param("sourceUrl", release.url())
                        .param("evidence", summary(release))
                        .param("snapshotId", snapshotId)
                        .update();
            }
        }
        jdbcClient.sql("""
                update tracked_project
                set last_sync_at = :fetchedAt, next_sync_at = :nextSyncAt,
                    last_sync_status = 'SUCCEEDED', last_sync_error = null,
                    consecutive_failures = 0, sync_locked_until = null, updated_at = :fetchedAt
                where id = :projectId
                """)
                .param("fetchedAt", timestamp(fetchedAt))
                .param("nextSyncAt", timestamp(nextSyncAt))
                .param("projectId", project.id())
                .update();
        appendJob(project, "SUCCEEDED", project.consecutiveFailures() + 1, null, fetchedAt, fetchedAt);
        return new SyncResult(releases.size(), newEvents);
    }

    @Override
    @Transactional
    public void completeFailedSync(
            TrackedProject project,
            String errorCode,
            String errorMessage,
            Instant failedAt,
            Instant nextRetryAt) {
        int failures = project.consecutiveFailures() + 1;
        String status = failures >= 3 ? "FAILED" : "RETRY_WAIT";
        String safeError = sanitize(errorCode + ": " + errorMessage);
        jdbcClient.sql("""
                update tracked_project
                set last_sync_at = :failedAt, next_sync_at = :nextRetryAt,
                    last_sync_status = :status, last_sync_error = :error,
                    consecutive_failures = :failures, sync_locked_until = null, updated_at = :failedAt
                where id = :projectId
                """)
                .param("failedAt", timestamp(failedAt))
                .param("nextRetryAt", timestamp(nextRetryAt))
                .param("status", status)
                .param("error", safeError)
                .param("failures", failures)
                .param("projectId", project.id())
                .update();
        appendJob(project, status, failures, safeError, nextRetryAt, failedAt);
    }

    @Override
    public UpdatePage listUpdates(
            ActorContext actor, int page, int size, UUID projectId, boolean unreadOnly) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(1, Math.min(size, 100));
        String filters = filters(projectId, unreadOnly);
        JdbcClient.StatementSpec count = jdbcClient.sql("""
                select count(*)
                from intelligence_event event
                join tracked_project project on project.id = event.project_id
                join user_project_watch watch
                  on watch.project_id = project.id and watch.user_id = :userId
                 and watch.workspace_id = :workspaceId and watch.enabled = true
                left join user_event_read event_read
                  on event_read.event_id = event.id and event_read.user_id = :userId
                where project.workspace_id = :workspaceId
                """ + filters)
                .param("userId", actor.userId())
                .param("workspaceId", actor.workspaceId());
        if (projectId != null) count = count.param("projectId", projectId);
        long total = count.query(Long.class).single();

        JdbcClient.StatementSpec query = jdbcClient.sql("""
                select event.id as event_id, project.id as project_id,
                       project.repository_name, project.repository_owner,
                       snapshot.version_tag, event.title, event.summary, snapshot.source_url,
                       coalesce((event.payload ->> 'prerelease')::boolean, false) as prerelease,
                       event.occurred_at, snapshot.collected_at,
                       event_read.read_at is not null as is_read,
                       analysis.id as analysis_id, analysis.status as analysis_status,
                       analysis.risk_level, analysis.recommendation,
                       analysis.one_line_summary as intelligence_summary
                from intelligence_event event
                join source_snapshot snapshot on snapshot.id = event.snapshot_id
                join tracked_project project on project.id = event.project_id
                join user_project_watch watch
                  on watch.project_id = project.id and watch.user_id = :userId
                 and watch.workspace_id = :workspaceId and watch.enabled = true
                left join user_event_read event_read
                  on event_read.event_id = event.id and event_read.user_id = :userId
                left join intelligence_analysis analysis on analysis.event_id = event.id
                where project.workspace_id = :workspaceId
                """ + filters + """
                order by event.occurred_at desc nulls last, event.created_at desc
                limit :size offset :offset
                """)
                .param("userId", actor.userId())
                .param("workspaceId", actor.workspaceId())
                .param("size", safeSize)
                .param("offset", safePage * safeSize);
        if (projectId != null) query = query.param("projectId", projectId);
        List<ProjectUpdate> items = query.query((resultSet, rowNum) -> new ProjectUpdate(
                resultSet.getObject("event_id", UUID.class),
                resultSet.getObject("project_id", UUID.class),
                resultSet.getString("repository_name"),
                resultSet.getString("repository_owner"),
                resultSet.getString("version_tag"),
                resultSet.getString("title"),
                resultSet.getString("summary"),
                resultSet.getString("source_url"),
                resultSet.getBoolean("prerelease"),
                instant(resultSet, "occurred_at"),
                instant(resultSet, "collected_at"),
                resultSet.getBoolean("is_read"),
                resultSet.getObject("analysis_id", UUID.class),
                resultSet.getString("analysis_status"),
                resultSet.getString("risk_level"),
                resultSet.getString("recommendation"),
                resultSet.getString("intelligence_summary")))
                .list();
        return new UpdatePage(items, safePage, safeSize, total, unreadCount(actor));
    }

    @Override
    public long unreadCount(ActorContext actor) {
        return jdbcClient.sql("""
                select count(*)
                from intelligence_event event
                join tracked_project project on project.id = event.project_id
                join user_project_watch watch
                  on watch.project_id = project.id and watch.user_id = :userId
                 and watch.workspace_id = :workspaceId and watch.enabled = true
                left join user_event_read event_read
                  on event_read.event_id = event.id and event_read.user_id = :userId
                where project.workspace_id = :workspaceId and event_read.event_id is null
                """)
                .param("userId", actor.userId())
                .param("workspaceId", actor.workspaceId())
                .query(Long.class).single();
    }

    @Override
    public boolean markRead(ActorContext actor, UUID eventId, Instant readAt) {
        int updated = jdbcClient.sql("""
                insert into user_event_read (user_id, workspace_id, event_id, read_at)
                select :userId, :workspaceId, event.id, :readAt
                from intelligence_event event
                join tracked_project project on project.id = event.project_id
                join user_project_watch watch
                  on watch.project_id = project.id and watch.user_id = :userId
                 and watch.workspace_id = :workspaceId and watch.enabled = true
                where event.id = :eventId and project.workspace_id = :workspaceId
                on conflict (user_id, event_id) do update set read_at = excluded.read_at
                """)
                .param("userId", actor.userId())
                .param("workspaceId", actor.workspaceId())
                .param("eventId", eventId)
                .param("readAt", timestamp(readAt))
                .update();
        return updated > 0;
    }

    @Override
    public int markAllRead(ActorContext actor, Instant readAt) {
        return jdbcClient.sql("""
                insert into user_event_read (user_id, workspace_id, event_id, read_at)
                select :userId, :workspaceId, event.id, :readAt
                from intelligence_event event
                join tracked_project project on project.id = event.project_id
                join user_project_watch watch
                  on watch.project_id = project.id and watch.user_id = :userId
                 and watch.workspace_id = :workspaceId and watch.enabled = true
                where project.workspace_id = :workspaceId
                on conflict (user_id, event_id) do nothing
                """)
                .param("userId", actor.userId())
                .param("workspaceId", actor.workspaceId())
                .param("readAt", timestamp(readAt))
                .update();
    }

    @Override
    public List<CollectionStatus> collectionStatus(UUID workspaceId) {
        return jdbcClient.sql("""
                select id, repository_name, repository_owner, last_sync_status,
                       last_sync_at, next_sync_at, consecutive_failures, last_sync_error
                from tracked_project
                where workspace_id = :workspaceId and enabled = true
                order by priority, repository_name
                """)
                .param("workspaceId", workspaceId)
                .query((resultSet, rowNum) -> new CollectionStatus(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("repository_name"),
                        resultSet.getString("repository_owner"),
                        resultSet.getString("last_sync_status"),
                        instant(resultSet, "last_sync_at"),
                        instant(resultSet, "next_sync_at"),
                        resultSet.getInt("consecutive_failures"),
                        resultSet.getString("last_sync_error")))
                .list();
    }

    @Override
    public boolean requestSync(UUID workspaceId, UUID projectId, Instant now) {
        return jdbcClient.sql("""
                update tracked_project
                set next_sync_at = :now, updated_at = :now
                where id = :projectId and workspace_id = :workspaceId and enabled = true
                """)
                .param("now", timestamp(now))
                .param("projectId", projectId)
                .param("workspaceId", workspaceId)
                .update() == 1;
    }

    private void appendJob(
            TrackedProject project,
            String status,
            int attempts,
            String error,
            Instant scheduledAt,
            Instant now) {
        jdbcClient.sql("""
                insert into job_task
                    (id, workspace_id, project_id, job_type, business_key, status,
                     attempts, max_attempts, scheduled_at, last_error, created_at, updated_at)
                values (:id, :workspaceId, :projectId, 'GITHUB_RELEASE_SYNC', :businessKey, :status,
                        :attempts, 3, :scheduledAt, :error, :now, :now)
                """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", project.workspaceId())
                .param("projectId", project.id())
                .param("businessKey", project.id() + ":" + now.toEpochMilli() + ":" + UUID.randomUUID())
                .param("status", status)
                .param("attempts", attempts)
                .param("scheduledAt", timestamp(scheduledAt))
                .param("error", error)
                .param("now", timestamp(now))
                .update();
    }

    private String releaseJson(GitHubRelease release) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("projectId", release.projectId());
        value.put("projectName", release.projectName());
        value.put("tagName", release.tagName());
        value.put("releaseName", release.releaseName());
        value.put("publishedAt", release.publishedAt().toString());
        value.put("url", release.url());
        value.put("prerelease", release.prerelease());
        value.put("notesExcerpt", release.notesExcerpt());
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize GitHub Release", exception);
        }
    }

    private static String filters(UUID projectId, boolean unreadOnly) {
        StringBuilder filters = new StringBuilder();
        if (projectId != null) filters.append(" and project.id = :projectId\n");
        if (unreadOnly) filters.append(" and event_read.event_id is null\n");
        return filters.toString();
    }

    private static String summary(GitHubRelease release) {
        if (release.notesExcerpt() == null || release.notesExcerpt().isBlank()) {
            return release.projectName() + " 发布了 " + release.tagName() + "。";
        }
        return release.notesExcerpt();
    }

    private static String sanitize(String value) {
        String sanitized = value == null ? "Unknown collection error" : value.replace("\u0000", "").trim();
        return sanitized.length() <= 1000 ? sanitized : sanitized.substring(0, 1000);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(java.sql.ResultSet resultSet, String column) throws java.sql.SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
