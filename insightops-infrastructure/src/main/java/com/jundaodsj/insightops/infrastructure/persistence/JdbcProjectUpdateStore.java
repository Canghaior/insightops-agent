package com.jundaodsj.insightops.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.project.application.ProjectUpdateStore;
import com.jundaodsj.insightops.tool.application.github.GitHubRelease;
import com.jundaodsj.insightops.tool.application.github.GitHubProjectEvent;
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
        UUID lockToken = UUID.randomUUID();
        return jdbcClient.sql("""
                with due as (
                    select project.id
                    from tracked_project project
                    where project.enabled = true
                      and coalesce(project.next_sync_at, :now) <= :now
                      and (project.sync_locked_until is null or project.sync_locked_until <= :now)
                    order by coalesce(project.next_sync_at, project.created_at), project.priority
                    for update skip locked
                    limit :limit
                )
                update tracked_project project
                set sync_locked_until = :lockUntil,
                    sync_lock_token = :lockToken,
                    sync_heartbeat_at = :now,
                    sync_current_source_type = 'GITHUB_RELEASE',
                    sync_discovered_count = 0,
                    sync_stored_count = 0,
                    last_sync_status = 'RUNNING',
                    last_sync_error = null,
                    updated_at = :now
                from due
                where project.id = due.id
                returning project.id, project.workspace_id, project.repository_name,
                          project.repository_owner, project.sync_interval_hours,
                          project.consecutive_failures, project.sync_lock_token
                """)
                .param("now", timestamp(now))
                .param("lockUntil", timestamp(now.plus(lockDuration)))
                .param("lockToken", lockToken)
                .param("limit", Math.max(1, Math.min(limit, 20)))
                .query((resultSet, rowNum) -> new TrackedProject(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("workspace_id", UUID.class),
                        resultSet.getString("repository_name"),
                        resultSet.getString("repository_owner"),
                        resultSet.getString("repository_name"),
                        resultSet.getInt("sync_interval_hours"),
                        resultSet.getInt("consecutive_failures"),
                        resultSet.getObject("sync_lock_token", UUID.class)))
                .list();
    }

    @Override
    public boolean renewSyncLease(
            TrackedProject project, String currentSourceType, int discoveredCount,
            int storedCount, Instant now, Duration lockDuration) {
        if (project.lockToken() == null) return true;
        return jdbcClient.sql("""
                update tracked_project
                set sync_heartbeat_at=:now, sync_locked_until=:lockUntil,
                    sync_current_source_type=:sourceType,
                    sync_discovered_count=:discoveredCount,
                    sync_stored_count=:storedCount,
                    updated_at=:now
                where id=:projectId and sync_lock_token=:lockToken
                  and last_sync_status='RUNNING' and sync_locked_until > :now
                """)
                .param("now", timestamp(now))
                .param("lockUntil", timestamp(now.plus(lockDuration)))
                .param("sourceType", currentSourceType)
                .param("discoveredCount", Math.max(0, discoveredCount))
                .param("storedCount", Math.max(0, storedCount))
                .param("projectId", project.id())
                .param("lockToken", project.lockToken())
                .update() == 1;
    }

    @Override
    @Transactional
    public int storeProjectEvents(
            TrackedProject project, List<GitHubProjectEvent> events, Instant fetchedAt) {
        if (!ownsSyncLease(project, fetchedAt)) {
            throw new IllegalStateException("GitHub collection lease was lost");
        }
        int newEvents = 0;
        for (GitHubProjectEvent event : events) {
            Optional<UUID> existingSnapshot = jdbcClient.sql("""
                    select id from source_snapshot
                    where project_id=:projectId and source_type=:sourceType and external_id=:externalId
                    """)
                    .param("projectId", project.id())
                    .param("sourceType", event.eventType())
                    .param("externalId", event.externalId())
                    .query(UUID.class).optional();
            UUID snapshotId = existingSnapshot.orElseGet(UUID::randomUUID);
            if (existingSnapshot.isEmpty()) {
                jdbcClient.sql("""
                        insert into source_snapshot
                            (id, project_id, source_type, external_id, version_tag, source_url,
                             content_sha256, raw_content, published_at, collected_at)
                        values (:id, :projectId, :sourceType, :externalId, null, :sourceUrl,
                                :contentHash, cast(:raw as jsonb), :publishedAt, :collectedAt)
                        """)
                        .param("id", snapshotId)
                        .param("projectId", project.id())
                        .param("sourceType", event.eventType())
                        .param("externalId", event.externalId())
                        .param("sourceUrl", event.sourceUrl())
                        .param("contentHash", sha256(event.rawJson()))
                        .param("raw", event.rawJson())
                        .param("publishedAt", timestamp(event.occurredAt()))
                        .param("collectedAt", timestamp(fetchedAt)).update();
                UUID eventId = UUID.randomUUID();
                jdbcClient.sql("""
                        insert into intelligence_event
                            (id, project_id, snapshot_id, event_type, title, summary,
                             importance, occurred_at, payload, analysis_eligible, state,
                             author_login, labels, risk_level, created_at, updated_at)
                        values (:id, :projectId, :snapshotId, :eventType, :title, :summary,
                                :importance, :occurredAt, cast(:payload as jsonb), :analysisEligible, :state,
                                :authorLogin, :labels, :riskLevel, :createdAt, :updatedAt)
                        """)
                        .param("id", eventId)
                        .param("projectId", project.id())
                        .param("snapshotId", snapshotId)
                        .param("eventType", event.eventType())
                        .param("title", sanitizeText(event.title(), 512))
                        .param("summary", sanitizeText(event.summary(), 4000))
                        .param("importance", event.importance())
                        .param("analysisEligible", event.importance() >= 4)
                        .param("occurredAt", timestamp(event.occurredAt()))
                        .param("payload", event.rawJson())
                        .param("state", event.state())
                        .param("authorLogin", event.authorLogin())
                        .param("labels", event.labels().toArray(String[]::new))
                        .param("riskLevel", event.riskLevel())
                        .param("createdAt", timestamp(fetchedAt))
                        .param("updatedAt", timestamp(event.updatedAt())).update();
                jdbcClient.sql("""
                        insert into event_evidence
                            (id, event_id, snapshot_id, source_url, evidence_text, sort_order, created_at)
                        values (:id, :eventId, :snapshotId, :sourceUrl, :evidence, 0, :createdAt)
                        """)
                        .param("id", UUID.randomUUID())
                        .param("eventId", eventId)
                        .param("snapshotId", snapshotId)
                        .param("sourceUrl", event.sourceUrl())
                        .param("evidence", sanitizeText(event.summary(), 4000))
                        .param("createdAt", timestamp(fetchedAt)).update();
                applyWatchRules(eventId, fetchedAt);
                newEvents++;
            } else {
                jdbcClient.sql("""
                        update source_snapshot set source_url=:sourceUrl, content_sha256=:contentHash,
                            raw_content=cast(:raw as jsonb), published_at=:publishedAt,
                            collected_at=:collectedAt where id=:id
                        """)
                        .param("sourceUrl", event.sourceUrl())
                        .param("contentHash", sha256(event.rawJson()))
                        .param("raw", event.rawJson())
                        .param("publishedAt", timestamp(event.occurredAt()))
                        .param("collectedAt", timestamp(fetchedAt))
                        .param("id", snapshotId).update();
                jdbcClient.sql("""
                        update intelligence_event set title=:title, summary=:summary,
                            importance=:importance, occurred_at=:occurredAt,
                            payload=cast(:payload as jsonb), state=:state,
                            author_login=:authorLogin, labels=:labels, risk_level=:riskLevel,
                            updated_at=:updatedAt where snapshot_id=:snapshotId
                        """)
                        .param("title", sanitizeText(event.title(), 512))
                        .param("summary", sanitizeText(event.summary(), 4000))
                        .param("importance", event.importance())
                        .param("occurredAt", timestamp(event.occurredAt()))
                        .param("payload", event.rawJson())
                        .param("state", event.state())
                        .param("authorLogin", event.authorLogin())
                        .param("labels", event.labels().toArray(String[]::new))
                        .param("riskLevel", event.riskLevel())
                        .param("updatedAt", timestamp(event.updatedAt()))
                        .param("snapshotId", snapshotId).update();
                jdbcClient.sql("""
                        update event_evidence set source_url=:sourceUrl, evidence_text=:evidence
                        where snapshot_id=:snapshotId
                        """)
                        .param("sourceUrl", event.sourceUrl())
                        .param("evidence", sanitizeText(event.summary(), 4000))
                        .param("snapshotId", snapshotId).update();
            }
        }
        return newEvents;
    }

    @Override
    @Transactional
    public SyncResult completeSuccessfulSync(
            TrackedProject project,
            List<GitHubRelease> releases,
            Instant fetchedAt,
            Instant nextSyncAt) {
        if (!ownsSyncLease(project, fetchedAt)) {
            throw new IllegalStateException("GitHub collection lease was lost");
        }
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
                applyWatchRules(eventId, fetchedAt);
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
                    consecutive_failures = 0, sync_locked_until = null,
                    sync_lock_token = null, sync_heartbeat_at = :fetchedAt,
                    sync_current_source_type = null, updated_at = :fetchedAt
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
        if (!ownsSyncLease(project, failedAt)) return;
        int failures = project.consecutiveFailures() + 1;
        String status = failures >= 3 ? "FAILED" : "RETRY_WAIT";
        String safeError = sanitize(errorCode + ": " + errorMessage);
        jdbcClient.sql("""
                update tracked_project
                set last_sync_at = :failedAt, next_sync_at = :nextRetryAt,
                    last_sync_status = :status, last_sync_error = :error,
                    consecutive_failures = :failures, sync_locked_until = null,
                    sync_lock_token = null, sync_heartbeat_at = :failedAt,
                    sync_current_source_type = null, updated_at = :failedAt
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
        return listUpdates(actor, page, size, projectId, unreadOnly, null, null, false);
    }

    @Override
    public UpdatePage listUpdates(
            ActorContext actor, int page, int size, UUID projectId, boolean unreadOnly,
            String eventType, String riskLevel, boolean matchedOnly) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(1, Math.min(size, 100));
        String filters = filters(projectId, unreadOnly, eventType, riskLevel, matchedOnly);
        JdbcClient.StatementSpec count = jdbcClient.sql("""
                select count(*)
                from intelligence_event event
                join tracked_project project on project.id = event.project_id
                join user_project_watch watch
                  on watch.project_id = project.id and watch.user_id = :userId
                 and watch.workspace_id = :workspaceId and watch.enabled = true
                left join user_event_read event_read
                  on event_read.event_id = event.id and event_read.user_id = :userId
                left join intelligence_analysis analysis on analysis.event_id = event.id
                where project.workspace_id = :workspaceId
                """ + filters)
                .param("userId", actor.userId())
                .param("workspaceId", actor.workspaceId());
        if (projectId != null) count = count.param("projectId", projectId);
        if (eventType != null && !eventType.isBlank()) count = count.param("eventType", eventType);
        if (riskLevel != null && !riskLevel.isBlank()) count = count.param("riskLevel", riskLevel);
        long total = count.query(Long.class).single();

        JdbcClient.StatementSpec query = jdbcClient.sql("""
                select event.id as event_id, project.id as project_id,
                       project.repository_name, project.repository_owner,
                       event.event_type, snapshot.version_tag, event.title, event.summary, snapshot.source_url,
                       event.state, event.author_login, event.labels, event.importance,
                       coalesce((event.payload ->> 'prerelease')::boolean, false) as prerelease,
                       event.occurred_at, snapshot.collected_at,
                       event_read.read_at is not null as is_read,
                       analysis.id as analysis_id, analysis.status as analysis_status,
                       coalesce(event.risk_level, analysis.risk_level) as risk_level,
                       analysis.recommendation, analysis.one_line_summary as intelligence_summary,
                       (select count(*) from event_rule_match rule_match
                         where rule_match.event_id=event.id and rule_match.user_id=:userId) as matched_rule_count
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
        if (eventType != null && !eventType.isBlank()) query = query.param("eventType", eventType);
        if (riskLevel != null && !riskLevel.isBlank()) query = query.param("riskLevel", riskLevel);
        List<ProjectUpdate> items = query.query((resultSet, rowNum) -> new ProjectUpdate(
                resultSet.getObject("event_id", UUID.class),
                resultSet.getObject("project_id", UUID.class),
                resultSet.getString("repository_name"),
                resultSet.getString("repository_owner"),
                resultSet.getString("event_type"),
                resultSet.getString("version_tag"),
                resultSet.getString("title"),
                resultSet.getString("summary"),
                resultSet.getString("source_url"),
                resultSet.getString("state"),
                resultSet.getString("author_login"),
                array(resultSet, "labels"),
                resultSet.getInt("importance"),
                resultSet.getBoolean("prerelease"),
                instant(resultSet, "occurred_at"),
                instant(resultSet, "collected_at"),
                resultSet.getBoolean("is_read"),
                resultSet.getObject("analysis_id", UUID.class),
                resultSet.getString("analysis_status"),
                resultSet.getString("risk_level"),
                resultSet.getString("recommendation"),
                resultSet.getString("intelligence_summary"),
                resultSet.getLong("matched_rule_count")))
                .list();
        return new UpdatePage(items, safePage, safeSize, total, unreadCount(actor));
    }

    @Override
    public List<EventEvidence> searchEvents(
            UUID workspaceId, String query, int limit, List<String> eventTypes) {
        String normalized = query == null ? "" : query.replace("\u0000", "").trim();
        int safeLimit = Math.max(1, Math.min(limit, 20));
        String typeFilter = eventTypes == null || eventTypes.isEmpty()
                ? "" : " and event.event_type = any(:eventTypes)\n";
        JdbcClient.StatementSpec statement = jdbcClient.sql("""
                select event.id as event_id, project.id as project_id,
                       project.repository_name, event.event_type, event.title, event.summary,
                       snapshot.source_url, event.state, event.risk_level, event.importance,
                       event.occurred_at
                from intelligence_event event
                join tracked_project project on project.id=event.project_id
                join source_snapshot snapshot on snapshot.id=event.snapshot_id
                where project.workspace_id=:workspaceId
                  and (:query='' or lower(event.title || ' ' || event.summary || ' ' || project.repository_name)
                       like '%' || lower(:query) || '%')
                """ + typeFilter + """
                order by event.importance desc, event.occurred_at desc nulls last
                limit :limit
                """)
                .param("workspaceId", workspaceId)
                .param("query", normalized)
                .param("limit", safeLimit);
        if (!typeFilter.isBlank()) statement = statement.param("eventTypes", eventTypes.toArray(String[]::new));
        return statement.query((rs, rowNum) -> new EventEvidence(
                rs.getObject("event_id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getString("repository_name"), rs.getString("event_type"),
                rs.getString("title"), rs.getString("summary"), rs.getString("source_url"),
                rs.getString("state"), rs.getString("risk_level"), rs.getInt("importance"),
                instant(rs, "occurred_at"))).list();
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
                       last_sync_at, next_sync_at, consecutive_failures, last_sync_error,
                       sync_current_source_type, sync_heartbeat_at,
                       sync_discovered_count, sync_stored_count
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
                        resultSet.getString("last_sync_error"),
                        resultSet.getString("sync_current_source_type"),
                        instant(resultSet, "sync_heartbeat_at"),
                        resultSet.getInt("sync_discovered_count"),
                        resultSet.getInt("sync_stored_count")))
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

    private boolean ownsSyncLease(TrackedProject project, Instant now) {
        if (project.lockToken() == null) return true;
        return jdbcClient.sql("""
                select id from tracked_project
                where id=:projectId and sync_lock_token=:lockToken
                  and last_sync_status='RUNNING'
                for update
                """)
                .param("projectId", project.id()).param("lockToken", project.lockToken())
                .query(UUID.class).optional().isPresent();
    }

    private void applyWatchRules(UUID eventId, Instant matchedAt) {
        jdbcClient.sql("""
                insert into event_rule_match (rule_id, event_id, user_id, workspace_id, matched_at)
                select rule.id, event.id, rule.user_id, rule.workspace_id, :matchedAt
                from intelligence_event event
                join tracked_project project on project.id=event.project_id
                join user_watch_rule rule on rule.workspace_id=project.workspace_id and rule.enabled=true
                where event.id=:eventId
                  and (rule.project_id is null or rule.project_id=event.project_id)
                  and event.importance >= rule.minimum_importance
                  and (cardinality(rule.event_types)=0 or event.event_type=any(rule.event_types))
                  and (cardinality(rule.keywords)=0 or exists (
                      select 1 from unnest(rule.keywords) keyword
                      where lower(event.title || ' ' || event.summary || ' ' || array_to_string(event.labels, ' '))
                            like '%' || lower(keyword) || '%'
                  ))
                  and not exists (
                      select 1 from unnest(rule.excluded_keywords) keyword
                      where lower(event.title || ' ' || event.summary || ' ' || array_to_string(event.labels, ' '))
                            like '%' || lower(keyword) || '%'
                  )
                on conflict (rule_id, event_id) do nothing
                """)
                .param("eventId", eventId)
                .param("matchedAt", timestamp(matchedAt)).update();
        jdbcClient.sql("""
                insert into user_notification
                    (id, user_id, workspace_id, notification_type, entity_id,
                     severity, title, body, created_at)
                select gen_random_uuid(), rule_match.user_id, rule_match.workspace_id, 'RULE_MATCH', event.id,
                       case when event.importance >= 5 then 'CRITICAL'
                            when event.importance >= 4 then 'WARNING' else 'INFO' end,
                       '关注规则命中：' || left(event.title, 230),
                       left(project.repository_owner || '/' || project.repository_name || ' · '
                            || event.event_type || ' · ' || event.summary, 1000),
                       :createdAt
                from event_rule_match rule_match
                join user_watch_rule rule on rule.id=rule_match.rule_id and rule.immediate_notification=true
                join intelligence_event event on event.id=rule_match.event_id
                join tracked_project project on project.id=event.project_id
                where rule_match.event_id=:eventId
                on conflict (user_id, notification_type, entity_id) do nothing
                """)
                .param("eventId", eventId)
                .param("createdAt", timestamp(matchedAt)).update();
        jdbcClient.sql("""
                update intelligence_event event set analysis_eligible=true
                where event.id=:eventId and exists (
                    select 1 from event_rule_match rule_match
                    join user_watch_rule rule on rule.id=rule_match.rule_id
                    where rule_match.event_id=event.id and rule.include_in_digest=true
                )
                """).param("eventId", eventId).update();
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

    private static String filters(
            UUID projectId, boolean unreadOnly, String eventType,
            String riskLevel, boolean matchedOnly) {
        StringBuilder filters = new StringBuilder();
        if (projectId != null) filters.append(" and project.id = :projectId\n");
        if (unreadOnly) filters.append(" and event_read.event_id is null\n");
        if (eventType != null && !eventType.isBlank()) filters.append(" and event.event_type = :eventType\n");
        if (riskLevel != null && !riskLevel.isBlank()) {
            filters.append(" and coalesce(event.risk_level, analysis.risk_level) = :riskLevel\n");
        }
        if (matchedOnly) {
            filters.append(" and exists (select 1 from event_rule_match rule_match where rule_match.event_id=event.id and rule_match.user_id=:userId)\n");
        }
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

    private static String sanitizeText(String value, int maxLength) {
        String sanitized = value == null ? "" : value.replace("\u0000", "").trim();
        return sanitized.length() <= maxLength ? sanitized : sanitized.substring(0, maxLength);
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

    private static List<String> array(java.sql.ResultSet resultSet, String column)
            throws java.sql.SQLException {
        java.sql.Array value = resultSet.getArray(column);
        if (value == null) return List.of();
        Object raw = value.getArray();
        if (raw instanceof String[] strings) return List.of(strings);
        if (raw instanceof Object[] objects) {
            return java.util.Arrays.stream(objects).map(String::valueOf).toList();
        }
        return List.of();
    }
}
