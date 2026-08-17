package com.jundaodsj.insightops.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.intelligence.application.IntelligenceStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcIntelligenceStore implements IntelligenceStore {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final TypeReference<List<UUID>> UUID_LIST = new TypeReference<>() { };
    private static final TypeReference<List<AnalysisSummary>> SUMMARY_LIST = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcIntelligenceStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional
    public List<AnalysisTask> claimDueAnalyses(Instant now, Duration lockDuration, int limit, int dailyLimit) {
        jdbc.sql("""
                insert into intelligence_analysis
                    (id, workspace_id, project_id, event_id, status, automatic,
                     attempts, max_attempts, next_attempt_at, created_at, updated_at)
                select gen_random_uuid(), project.workspace_id, event.project_id, event.id,
                       'QUEUED', true, 0, 3, :now, :now, :now
                from intelligence_event event
                join tracked_project project on project.id = event.project_id
                where event.analysis_eligible = true
                  and project.enabled = true
                  and exists (select 1 from user_project_watch watch
                              where watch.project_id = project.id and watch.enabled = true)
                  and not exists (select 1 from intelligence_analysis analysis
                                  where analysis.event_id = event.id)
                on conflict (event_id) do nothing
                """)
                .param("now", timestamp(now))
                .update();

        return jdbc.sql("""
                with due as (
                    select analysis.id
                    from intelligence_analysis analysis
                    where analysis.status in ('QUEUED', 'RETRY_WAIT')
                      and analysis.next_attempt_at <= :now
                      and (analysis.locked_until is null or analysis.locked_until <= :now)
                      and (analysis.automatic = false or (
                          select count(*) from intelligence_analysis used
                          where used.workspace_id = analysis.workspace_id
                            and used.automatic = true
                            and used.started_at >= date_trunc('day', cast(:now as timestamptz))
                      ) < :dailyLimit)
                    order by analysis.automatic, analysis.next_attempt_at, analysis.created_at
                    for update skip locked
                    limit :limit
                )
                update intelligence_analysis analysis
                set status = 'RUNNING', attempts = attempts + 1, started_at = :now,
                    locked_until = :lockUntil, last_error = null, updated_at = :now
                from due
                where analysis.id = due.id
                returning analysis.id
                """)
                .param("now", timestamp(now))
                .param("lockUntil", timestamp(now.plus(lockDuration)))
                .param("dailyLimit", Math.max(1, dailyLimit))
                .param("limit", Math.max(1, Math.min(limit, 20)))
                .query(UUID.class)
                .list().stream()
                .map(this::loadTask)
                .toList();
    }

    @Override
    @Transactional
    public void completeAnalysis(AnalysisTask task, AnalysisResult result, ModelAudit audit, Instant completedAt) {
        jdbc.sql("""
                update intelligence_analysis
                set status = 'SUCCEEDED', risk_level = :risk, recommendation = :recommendation,
                    evidence_status = :evidenceStatus, one_line_summary = :summary,
                    major_changes = cast(:changes as jsonb), java_impact = :javaImpact,
                    upgrade_value = :upgradeValue, risks = cast(:risks as jsonb),
                    recommended_actions = cast(:actions as jsonb), evidence_urls = cast(:urls as jsonb),
                    model_provider = :provider, model_name = :model,
                    prompt_tokens = :promptTokens, completion_tokens = :completionTokens,
                    estimated_cost_cny = :cost, pricing_effective_date = :pricingDate,
                    completed_at = :completedAt, locked_until = null, last_error = null,
                    updated_at = :completedAt
                where id = :id
                """)
                .param("risk", result.riskLevel())
                .param("recommendation", result.recommendation())
                .param("evidenceStatus", result.evidenceStatus())
                .param("summary", result.oneLineSummary())
                .param("changes", write(result.majorChanges()))
                .param("javaImpact", result.javaImpact())
                .param("upgradeValue", result.upgradeValue())
                .param("risks", write(result.risks()))
                .param("actions", write(result.recommendedActions()))
                .param("urls", write(result.evidenceUrls()))
                .param("provider", audit.provider())
                .param("model", audit.model())
                .param("promptTokens", audit.usage().inputTokens())
                .param("completionTokens", audit.usage().outputTokens())
                .param("cost", audit.estimatedCostCny())
                .param("pricingDate", audit.pricingEffectiveDate())
                .param("completedAt", timestamp(completedAt))
                .param("id", task.analysisId())
                .update();
        notifyWatchers(task, "ANALYSIS_READY", "INFO",
                task.repositoryName() + " " + task.versionTag() + " 情报分析已完成",
                result.oneLineSummary(), completedAt);
        if ("HIGH".equals(result.riskLevel())) {
            notifyWatchers(task, "HIGH_RISK", "CRITICAL",
                    task.repositoryName() + " " + task.versionTag() + " 存在高风险变更",
                    result.risks().isEmpty() ? result.oneLineSummary() : result.risks().getFirst(), completedAt);
        }
    }

    @Override
    @Transactional
    public void failAnalysis(AnalysisTask task, String errorCode, String errorMessage,
                             Instant failedAt, Instant nextAttemptAt, boolean terminal) {
        String safeError = safe(errorCode + ": " + errorMessage, 1000);
        jdbc.sql("""
                update intelligence_analysis
                set status = :status, next_attempt_at = :nextAttemptAt,
                    locked_until = null, last_error = :error, updated_at = :failedAt
                where id = :id
                """)
                .param("status", terminal ? "FAILED" : "RETRY_WAIT")
                .param("nextAttemptAt", timestamp(nextAttemptAt))
                .param("error", safeError)
                .param("failedAt", timestamp(failedAt))
                .param("id", task.analysisId())
                .update();
        if (terminal) {
            notifyWatchers(task, "ANALYSIS_FAILED", "WARNING",
                    task.repositoryName() + " " + task.versionTag() + " 情报分析失败",
                    "原始 Release 仍可正常查看，管理员可以手动重试。", failedAt);
        }
    }

    @Override
    @Transactional
    public boolean requestAnalysis(UUID workspaceId, UUID eventId, UUID requestedBy, Instant now) {
        int updated = jdbc.sql("""
                insert into intelligence_analysis
                    (id, workspace_id, project_id, event_id, status, automatic,
                     attempts, max_attempts, next_attempt_at, requested_by, created_at, updated_at)
                select gen_random_uuid(), project.workspace_id, event.project_id, event.id,
                       'QUEUED', false, 0, 3, :now, :requestedBy, :now, :now
                from intelligence_event event
                join tracked_project project on project.id = event.project_id
                where event.id = :eventId and project.workspace_id = :workspaceId
                on conflict (event_id) do update
                set status = 'QUEUED', automatic = false, next_attempt_at = excluded.next_attempt_at,
                    requested_by = excluded.requested_by, locked_until = null, last_error = null,
                    updated_at = excluded.updated_at
                where intelligence_analysis.status in ('FAILED', 'RETRY_WAIT')
                """)
                .param("now", timestamp(now))
                .param("requestedBy", requestedBy)
                .param("eventId", eventId)
                .param("workspaceId", workspaceId)
                .update();
        return updated == 1;
    }

    @Override
    public AnalysisPage listAnalyses(ActorContext actor, int page, int size, UUID projectId, String riskLevel) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, size));
        String filters = analysisFilters(projectId, riskLevel);
        JdbcClient.StatementSpec count = jdbc.sql(analysisBaseCount(filters))
                .param("userId", actor.userId()).param("workspaceId", actor.workspaceId());
        JdbcClient.StatementSpec query = jdbc.sql(analysisBaseSelect(filters) +
                        " order by coalesce(analysis.completed_at, analysis.created_at) desc limit :size offset :offset")
                .param("userId", actor.userId()).param("workspaceId", actor.workspaceId())
                .param("size", safeSize).param("offset", safePage * safeSize);
        if (projectId != null) { count = count.param("projectId", projectId); query = query.param("projectId", projectId); }
        if (riskLevel != null && !riskLevel.isBlank()) { count = count.param("risk", riskLevel); query = query.param("risk", riskLevel); }
        return new AnalysisPage(query.query(this::summary).list(), safePage, safeSize, count.query(Long.class).single());
    }

    @Override
    public Optional<AnalysisDetail> findAnalysis(ActorContext actor, UUID analysisId) {
        return jdbc.sql("""
                select analysis.*, event.title as release_title, event.summary as release_summary,
                       event.occurred_at, project.repository_name, snapshot.version_tag, snapshot.source_url
                from intelligence_analysis analysis
                join intelligence_event event on event.id = analysis.event_id
                join tracked_project project on project.id = analysis.project_id
                join source_snapshot snapshot on snapshot.id = event.snapshot_id
                join user_project_watch watch on watch.project_id = project.id
                    and watch.user_id = :userId and watch.workspace_id = :workspaceId and watch.enabled = true
                where analysis.id = :analysisId and analysis.workspace_id = :workspaceId
                """)
                .param("userId", actor.userId()).param("workspaceId", actor.workspaceId())
                .param("analysisId", analysisId)
                .query((rs, row) -> new AnalysisDetail(
                        summary(rs, row), strings(rs, "major_changes"), rs.getString("release_summary"),
                        rs.getString("java_impact"), rs.getString("upgrade_value"), strings(rs, "risks"),
                        strings(rs, "recommended_actions"), strings(rs, "evidence_urls"),
                        rs.getString("model_provider"), rs.getString("model_name"),
                        integer(rs, "prompt_tokens"), integer(rs, "completion_tokens"),
                        rs.getBigDecimal("estimated_cost_cny"), rs.getObject("pricing_effective_date", LocalDate.class),
                        rs.getInt("attempts"), rs.getString("last_error")))
                .optional();
    }

    @Override
    public List<AdminAnalysisStatus> adminStatuses(UUID workspaceId, int limit) {
        return jdbc.sql("""
                select analysis.id, analysis.event_id, project.repository_name, snapshot.version_tag,
                       analysis.status, analysis.risk_level, analysis.attempts, analysis.max_attempts,
                       analysis.automatic, analysis.next_attempt_at, analysis.completed_at, analysis.last_error
                from intelligence_analysis analysis
                join tracked_project project on project.id = analysis.project_id
                join intelligence_event event on event.id = analysis.event_id
                join source_snapshot snapshot on snapshot.id = event.snapshot_id
                where analysis.workspace_id = :workspaceId
                order by analysis.updated_at desc limit :limit
                """)
                .param("workspaceId", workspaceId).param("limit", Math.max(1, Math.min(200, limit)))
                .query((rs, row) -> new AdminAnalysisStatus(
                        rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class),
                        rs.getString("repository_name"), rs.getString("version_tag"), rs.getString("status"),
                        rs.getString("risk_level"), rs.getInt("attempts"), rs.getInt("max_attempts"),
                        rs.getBoolean("automatic"), instant(rs, "next_attempt_at"),
                        instant(rs, "completed_at"), rs.getString("last_error")))
                .list();
    }

    @Override
    public AnalysisMetrics analysisMetrics(UUID workspaceId, Instant dayStart) {
        return jdbc.sql("""
                select count(*) filter (where started_at >= :dayStart) as today_calls,
                       coalesce(sum(estimated_cost_cny) filter (where completed_at >= :dayStart), 0) as today_cost,
                       count(*) filter (where status in ('QUEUED','RETRY_WAIT','RUNNING')) as queued,
                       count(*) filter (where status = 'FAILED') as failed
                from intelligence_analysis where workspace_id = :workspaceId
                """)
                .param("dayStart", timestamp(dayStart)).param("workspaceId", workspaceId)
                .query((rs, row) -> new AnalysisMetrics(rs.getLong("today_calls"),
                        rs.getBigDecimal("today_cost"), rs.getLong("queued"), rs.getLong("failed"))).single();
    }

    @Override
    public DigestPreference getPreference(ActorContext actor) {
        return jdbc.sql("""
                select cadence, time_zone, delivery_hour, project_ids
                from digest_preference where user_id = :userId and workspace_id = :workspaceId
                """)
                .param("userId", actor.userId()).param("workspaceId", actor.workspaceId())
                .query((rs, row) -> new DigestPreference(rs.getString("cadence"), rs.getString("time_zone"),
                        rs.getInt("delivery_hour"), uuidList(rs.getString("project_ids"))))
                .optional().orElse(new DigestPreference("OFF", "Asia/Shanghai", 9, List.of()));
    }

    @Override
    public DigestPreference savePreference(ActorContext actor, String cadence, String timeZone,
                                           int deliveryHour, List<UUID> projectIds, Instant now) {
        String normalized = cadence == null ? "OFF" : cadence.trim().toUpperCase(Locale.ROOT);
        if (!List.of("OFF", "DAILY", "WEEKLY").contains(normalized)) throw new IllegalArgumentException("Invalid cadence");
        ZoneId.of(timeZone);
        if (deliveryHour < 0 || deliveryHour > 23) throw new IllegalArgumentException("Invalid delivery hour");
        List<UUID> safeProjects = projectIds == null ? List.of() : projectIds.stream().distinct().toList();
        jdbc.sql("""
                insert into digest_preference
                    (user_id, workspace_id, cadence, time_zone, delivery_hour, project_ids, created_at, updated_at)
                values (:userId, :workspaceId, :cadence, :timeZone, :deliveryHour, cast(:projects as jsonb), :now, :now)
                on conflict (user_id, workspace_id) do update
                set cadence = excluded.cadence, time_zone = excluded.time_zone,
                    delivery_hour = excluded.delivery_hour, project_ids = excluded.project_ids,
                    updated_at = excluded.updated_at
                """)
                .param("userId", actor.userId()).param("workspaceId", actor.workspaceId())
                .param("cadence", normalized).param("timeZone", timeZone).param("deliveryHour", deliveryHour)
                .param("projects", write(safeProjects)).param("now", timestamp(now)).update();
        return new DigestPreference(normalized, timeZone, deliveryHour, safeProjects);
    }

    @Override
    @Transactional
    public int refreshDueDigests(Instant now) {
        List<PreferenceRow> preferences = jdbc.sql("""
                select user_id, workspace_id, cadence, time_zone, delivery_hour, project_ids
                from digest_preference where cadence <> 'OFF'
                """)
                .query((rs, row) -> new PreferenceRow(rs.getObject("user_id", UUID.class),
                        rs.getObject("workspace_id", UUID.class), rs.getString("cadence"),
                        rs.getString("time_zone"), rs.getInt("delivery_hour"), uuidList(rs.getString("project_ids"))))
                .list();
        int created = 0;
        for (PreferenceRow preference : preferences) {
            ZoneId zone;
            try { zone = ZoneId.of(preference.timeZone()); } catch (RuntimeException ignored) { continue; }
            ZonedDateTime local = now.atZone(zone);
            if (local.getHour() < preference.deliveryHour()) continue;
            Period period = period(preference.cadence(), local);
            if (period == null || digestExists(preference, period)) continue;
            List<AnalysisSummary> items = digestItems(preference, period);
            if (items.isEmpty()) continue;
            UUID digestId = UUID.randomUUID();
            long high = items.stream().filter(item -> "HIGH".equals(item.riskLevel())).count();
            String title = ("DAILY".equals(preference.cadence()) ? "每日" : "每周")
                    + "技术情报摘要 · " + items.size() + " 条更新";
            jdbc.sql("""
                    insert into intelligence_digest
                        (id, user_id, workspace_id, cadence, period_start, period_end,
                         title, summary, item_count, high_risk_count, created_at)
                    values (:id, :userId, :workspaceId, :cadence, :start, :end,
                            :title, cast(:summary as jsonb), :itemCount, :highCount, :now)
                    on conflict (user_id, cadence, period_start, period_end) do nothing
                    """)
                    .param("id", digestId).param("userId", preference.userId())
                    .param("workspaceId", preference.workspaceId()).param("cadence", preference.cadence())
                    .param("start", timestamp(period.start())).param("end", timestamp(period.end()))
                    .param("title", title).param("summary", write(items)).param("itemCount", items.size())
                    .param("highCount", high).param("now", timestamp(now)).update();
            jdbc.sql("""
                    insert into user_notification
                        (id, user_id, workspace_id, notification_type, entity_id, severity, title, body, created_at)
                    values (gen_random_uuid(), :userId, :workspaceId, 'DIGEST_READY', :entityId,
                            :severity, :title, :body, :now)
                    on conflict (user_id, notification_type, entity_id) do nothing
                    """)
                    .param("userId", preference.userId()).param("workspaceId", preference.workspaceId())
                    .param("entityId", digestId).param("severity", high > 0 ? "WARNING" : "INFO")
                    .param("title", title).param("body", high > 0 ? high + " 条高风险更新需要关注" : "新的技术情报摘要已生成")
                    .param("now", timestamp(now)).update();
            created++;
        }
        return created;
    }

    @Override
    public DigestPage listDigests(ActorContext actor, int page, int size) {
        int safePage = Math.max(0, page); int safeSize = Math.max(1, Math.min(100, size));
        long total = jdbc.sql("select count(*) from intelligence_digest where user_id=:userId and workspace_id=:workspaceId")
                .param("userId", actor.userId()).param("workspaceId", actor.workspaceId()).query(Long.class).single();
        long unread = jdbc.sql("select count(*) from intelligence_digest where user_id=:userId and workspace_id=:workspaceId and read_at is null")
                .param("userId", actor.userId()).param("workspaceId", actor.workspaceId()).query(Long.class).single();
        List<DigestSummary> items = jdbc.sql("""
                select id, cadence, period_start, period_end, title, summary,
                       item_count, high_risk_count, read_at, created_at
                from intelligence_digest where user_id=:userId and workspace_id=:workspaceId
                order by created_at desc limit :size offset :offset
                """)
                .param("userId", actor.userId()).param("workspaceId", actor.workspaceId())
                .param("size", safeSize).param("offset", safePage * safeSize)
                .query((rs, row) -> new DigestSummary(rs.getObject("id", UUID.class), rs.getString("cadence"),
                        instant(rs, "period_start"), instant(rs, "period_end"), rs.getString("title"),
                        read(rs.getString("summary"), SUMMARY_LIST), rs.getInt("item_count"),
                        rs.getInt("high_risk_count"), rs.getObject("read_at") != null, instant(rs, "created_at"))).list();
        return new DigestPage(items, safePage, safeSize, total, unread);
    }

    @Override
    public boolean markDigestRead(ActorContext actor, UUID digestId, Instant readAt) {
        return jdbc.sql("update intelligence_digest set read_at=:readAt where id=:id and user_id=:userId and workspace_id=:workspaceId")
                .param("readAt", timestamp(readAt)).param("id", digestId).param("userId", actor.userId())
                .param("workspaceId", actor.workspaceId()).update() == 1;
    }

    @Override
    public NotificationPage listNotifications(ActorContext actor, int page, int size, boolean unreadOnly) {
        int safePage=Math.max(0,page), safeSize=Math.max(1,Math.min(100,size));
        String unread = unreadOnly ? " and read_at is null" : "";
        long total=jdbc.sql("select count(*) from user_notification where user_id=:userId and workspace_id=:workspaceId"+unread)
                .param("userId",actor.userId()).param("workspaceId",actor.workspaceId()).query(Long.class).single();
        List<Notification> items=jdbc.sql("""
                select id, notification_type, severity, title, body, entity_id, read_at, created_at
                from user_notification where user_id=:userId and workspace_id=:workspaceId
                """+unread+" order by created_at desc limit :size offset :offset")
                .param("userId",actor.userId()).param("workspaceId",actor.workspaceId())
                .param("size",safeSize).param("offset",safePage*safeSize)
                .query((rs,row)->new Notification(rs.getObject("id",UUID.class),rs.getString("notification_type"),
                        rs.getString("severity"),rs.getString("title"),rs.getString("body"),
                        rs.getObject("entity_id",UUID.class),rs.getObject("read_at")!=null,instant(rs,"created_at"))).list();
        return new NotificationPage(items,safePage,safeSize,total,unreadNotifications(actor));
    }

    @Override
    public long unreadNotifications(ActorContext actor) {
        return jdbc.sql("select count(*) from user_notification where user_id=:userId and workspace_id=:workspaceId and read_at is null")
                .param("userId",actor.userId()).param("workspaceId",actor.workspaceId()).query(Long.class).single();
    }

    @Override
    public boolean markNotificationRead(ActorContext actor, UUID notificationId, Instant readAt) {
        return jdbc.sql("update user_notification set read_at=:readAt where id=:id and user_id=:userId and workspace_id=:workspaceId")
                .param("readAt",timestamp(readAt)).param("id",notificationId).param("userId",actor.userId())
                .param("workspaceId",actor.workspaceId()).update()==1;
    }

    private AnalysisTask loadTask(UUID id) {
        return jdbc.sql("""
                select analysis.id, analysis.workspace_id, analysis.project_id, analysis.event_id,
                       project.repository_owner, project.repository_name, snapshot.version_tag,
                       event.title, event.summary, snapshot.source_url, event.occurred_at,
                       analysis.attempts, analysis.max_attempts, analysis.automatic
                from intelligence_analysis analysis
                join tracked_project project on project.id=analysis.project_id
                join intelligence_event event on event.id=analysis.event_id
                join source_snapshot snapshot on snapshot.id=event.snapshot_id
                where analysis.id=:id
                """).param("id",id).query((rs,row)->new AnalysisTask(
                        rs.getObject("id",UUID.class),rs.getObject("workspace_id",UUID.class),
                        rs.getObject("project_id",UUID.class),rs.getObject("event_id",UUID.class),
                        rs.getString("repository_owner"),rs.getString("repository_name"),rs.getString("version_tag"),
                        rs.getString("title"),rs.getString("summary"),rs.getString("source_url"),
                        instant(rs,"occurred_at"),rs.getInt("attempts"),rs.getInt("max_attempts"),rs.getBoolean("automatic"))).single();
    }

    private void notifyWatchers(AnalysisTask task,String type,String severity,String title,String body,Instant now) {
        jdbc.sql("""
                insert into user_notification
                    (id,user_id,workspace_id,notification_type,entity_id,severity,title,body,created_at)
                select gen_random_uuid(),watch.user_id,watch.workspace_id,:type,:entityId,:severity,:title,:body,:now
                from user_project_watch watch where watch.project_id=:projectId and watch.enabled=true
                on conflict (user_id,notification_type,entity_id) do nothing
                """).param("type",type).param("entityId",task.analysisId()).param("severity",severity)
                .param("title",safe(title,256)).param("body",safe(body,1000)).param("now",timestamp(now))
                .param("projectId",task.projectId()).update();
    }

    private List<AnalysisSummary> digestItems(PreferenceRow pref,Period period) {
        String projectFilter=pref.projectIds().isEmpty()?"":" and analysis.project_id in (:projectIds)";
        JdbcClient.StatementSpec query=jdbc.sql(analysisBaseSelect(projectFilter+" and analysis.completed_at>=:start and analysis.completed_at<:end")+
                " order by analysis.completed_at desc")
                .param("userId",pref.userId()).param("workspaceId",pref.workspaceId())
                .param("start",timestamp(period.start())).param("end",timestamp(period.end()));
        if(!pref.projectIds().isEmpty()) query=query.param("projectIds",pref.projectIds());
        return query.query(this::summary).list();
    }

    private boolean digestExists(PreferenceRow pref,Period period) {
        return jdbc.sql("select exists(select 1 from intelligence_digest where user_id=:userId and cadence=:cadence and period_start=:start and period_end=:end)")
                .param("userId",pref.userId()).param("cadence",pref.cadence()).param("start",timestamp(period.start()))
                .param("end",timestamp(period.end())).query(Boolean.class).single();
    }

    private static Period period(String cadence,ZonedDateTime local) {
        ZonedDateTime end=local.toLocalDate().atStartOfDay(local.getZone());
        if("DAILY".equals(cadence)) return new Period(end.minusDays(1).toInstant(),end.toInstant());
        if("WEEKLY".equals(cadence) && local.getDayOfWeek().getValue()==1)
            return new Period(end.minusWeeks(1).toInstant(),end.toInstant());
        return null;
    }

    private AnalysisSummary summary(ResultSet rs,int row) throws SQLException {
        return new AnalysisSummary(rs.getObject("id",UUID.class),rs.getObject("event_id",UUID.class),
                rs.getObject("project_id",UUID.class),rs.getString("repository_name"),rs.getString("version_tag"),
                rs.getString("release_title"),rs.getString("source_url"),rs.getString("status"),
                rs.getString("risk_level"),rs.getString("recommendation"),rs.getString("evidence_status"),
                rs.getString("one_line_summary"),instant(rs,"occurred_at"),instant(rs,"completed_at"));
    }

    private static String analysisBaseSelect(String filters) { return """
            select analysis.id,analysis.event_id,analysis.project_id,project.repository_name,
                   snapshot.version_tag,event.title as release_title,snapshot.source_url,
                   analysis.status,analysis.risk_level,analysis.recommendation,analysis.evidence_status,
                   analysis.one_line_summary,event.occurred_at,analysis.completed_at
            from intelligence_analysis analysis
            join intelligence_event event on event.id=analysis.event_id
            join tracked_project project on project.id=analysis.project_id
            join source_snapshot snapshot on snapshot.id=event.snapshot_id
            join user_project_watch watch on watch.project_id=project.id and watch.user_id=:userId
                and watch.workspace_id=:workspaceId and watch.enabled=true
            where analysis.workspace_id=:workspaceId
            """+filters;
    }

    private static String analysisBaseCount(String filters) { return """
            select count(*) from intelligence_analysis analysis
            join intelligence_event event on event.id=analysis.event_id
            join tracked_project project on project.id=analysis.project_id
            join user_project_watch watch on watch.project_id=project.id and watch.user_id=:userId
                and watch.workspace_id=:workspaceId and watch.enabled=true
            where analysis.workspace_id=:workspaceId
            """+filters;
    }

    private static String analysisFilters(UUID projectId,String risk) {
        StringBuilder value=new StringBuilder();
        if(projectId!=null)value.append(" and analysis.project_id=:projectId\n");
        if(risk!=null&&!risk.isBlank())value.append(" and analysis.risk_level=:risk\n");
        return value.toString();
    }

    private String write(Object value) { try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException(e);} }
    private <T> T read(String value,TypeReference<T> type){try{return json.readValue(value,type);}catch(JsonProcessingException e){throw new IllegalStateException(e);} }
    private List<String> strings(ResultSet rs,String column)throws SQLException{String value=rs.getString(column);return value==null?List.of():read(value,STRING_LIST);}
    private List<UUID> uuidList(String value){return value==null?List.of():read(value,UUID_LIST);}
    private static Integer integer(ResultSet rs,String column)throws SQLException{int value=rs.getInt(column);return rs.wasNull()?null:value;}
    private static Instant instant(ResultSet rs,String column)throws SQLException{OffsetDateTime value=rs.getObject(column,OffsetDateTime.class);return value==null?null:value.toInstant();}
    private static OffsetDateTime timestamp(Instant value){return OffsetDateTime.ofInstant(value,ZoneOffset.UTC);}
    private static String safe(String value,int max){String cleaned=value==null?"":value.replace("\u0000","").trim();return cleaned.length()<=max?cleaned:cleaned.substring(0,max);}

    private record PreferenceRow(UUID userId,UUID workspaceId,String cadence,String timeZone,int deliveryHour,List<UUID> projectIds){}
    private record Period(Instant start,Instant end){}
}
