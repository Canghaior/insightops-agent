package com.jundaodsj.insightops.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.infrastructure.delivery.DeliverySecretCipher;
import com.jundaodsj.insightops.infrastructure.delivery.WebhookUrlPolicy;
import com.jundaodsj.insightops.report.application.ReportDeliveryStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcReportDeliveryStore implements ReportDeliveryStore {
    private static final TypeReference<List<ReportItem>> ITEM_LIST = new TypeReference<>() { };
    private static final List<String> EVENT_TYPES = List.of(
            "GITHUB_RELEASE", "GITHUB_ISSUE", "GITHUB_PULL_REQUEST", "GITHUB_SECURITY_ADVISORY");
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final DeliverySecretCipher cipher;

    public JdbcReportDeliveryStore(JdbcClient jdbc, ObjectMapper json, DeliverySecretCipher cipher) {
        this.jdbc = jdbc;
        this.json = json;
        this.cipher = cipher;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReportItem> selectReportItems(ActorContext actor, ReportQuery query) {
        List<UUID> projects = distinct(query.projectIds());
        List<String> eventTypes = normalizedEventTypes(query.eventTypes());
        String projectFilter = projects.isEmpty() ? "" : " and analysis.project_id in (:projectIds)";
        String typeFilter = eventTypes.isEmpty() ? "" : " and event.event_type in (:eventTypes)";
        JdbcClient.StatementSpec statement = jdbc.sql("""
                select analysis.id as analysis_id,analysis.project_id,project.repository_name as project_name,
                       event.event_type,event.title as event_title,snapshot.version_tag,snapshot.source_url,
                       analysis.risk_level,analysis.recommendation,analysis.evidence_status,
                       analysis.one_line_summary,analysis.major_changes::text as major_changes,
                       analysis.java_impact,analysis.upgrade_value,analysis.risks::text as risks,
                       analysis.recommended_actions::text as recommended_actions,
                       analysis.evidence_urls::text as evidence_urls,event.occurred_at,analysis.completed_at
                from intelligence_analysis analysis
                join tracked_project project on project.id=analysis.project_id
                join intelligence_event event on event.id=analysis.event_id
                join source_snapshot snapshot on snapshot.id=event.snapshot_id
                where analysis.workspace_id=:workspaceId and analysis.status='SUCCEEDED'
                  and event.occurred_at>=:periodStart and event.occurred_at<:periodEnd
                  and exists(select 1 from user_project_watch watch
                      where watch.user_id=:userId and watch.workspace_id=:workspaceId
                        and watch.project_id=analysis.project_id and watch.enabled=true)
                """ + projectFilter + typeFilter
                + " order by case analysis.risk_level when 'HIGH' then 1 when 'MEDIUM' then 2 else 3 end,"
                + " event.occurred_at desc limit :limit")
                .param("userId", actor.userId()).param("workspaceId", actor.workspaceId())
                .param("periodStart", timestamp(query.periodStart())).param("periodEnd", timestamp(query.periodEnd()))
                .param("limit", Math.max(1, Math.min(100, query.maxItems())));
        if (!projects.isEmpty()) statement = statement.param("projectIds", projects);
        if (!eventTypes.isEmpty()) statement = statement.param("eventTypes", eventTypes);
        return statement.query(this::reportItem).list();
    }

    @Override
    @Transactional
    public ReportRecord createReport(ActorContext actor, UUID reportId, ReportQuery query,
                                     List<ReportItem> items, String markdown, Instant now) {
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("Report contains no intelligence items");
        int highRisk = (int) items.stream().filter(item -> "HIGH".equals(item.riskLevel())).count();
        List<UUID> projects = distinct(query.projectIds());
        List<String> eventTypes = normalizedEventTypes(query.eventTypes());
        jdbc.sql("""
                insert into research_report
                    (id,user_id,workspace_id,title,report_type,period_start,period_end,
                     project_ids,event_types,item_count,high_risk_count,snapshot,markdown_content,created_at)
                values (:id,:userId,:workspaceId,:title,'CUSTOM',:periodStart,:periodEnd,
                        :projectIds,:eventTypes,:itemCount,:highRiskCount,cast(:snapshot as jsonb),:markdown,:now)
                """).param("id", reportId).param("userId", actor.userId())
                .param("workspaceId", actor.workspaceId()).param("title", query.title())
                .param("periodStart", timestamp(query.periodStart())).param("periodEnd", timestamp(query.periodEnd()))
                .param("projectIds", projects.toArray(UUID[]::new))
                .param("eventTypes", eventTypes.toArray(String[]::new)).param("itemCount", items.size())
                .param("highRiskCount", highRisk).param("snapshot", write(items))
                .param("markdown", markdown).param("now", timestamp(now)).update();
        return findReport(actor, reportId).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public ReportPage listReports(ActorContext actor, int page, int size) {
        int safePage = Math.max(0, page), safeSize = Math.max(1, Math.min(100, size));
        long total = jdbc.sql("select count(*) from research_report where user_id=:userId and workspace_id=:workspaceId")
                .param("userId", actor.userId()).param("workspaceId", actor.workspaceId())
                .query(Long.class).single();
        List<ReportRecord> items = jdbc.sql(reportSelect() + """
                where report.user_id=:userId and report.workspace_id=:workspaceId
                order by report.created_at desc limit :size offset :offset
                """).param("userId", actor.userId()).param("workspaceId", actor.workspaceId())
                .param("size", safeSize).param("offset", safePage * safeSize)
                .query(this::report).list();
        return new ReportPage(items, safePage, safeSize, total);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReportRecord> findReport(ActorContext actor, UUID reportId) {
        return jdbc.sql(reportSelect() + " where report.id=:id and report.user_id=:userId and report.workspace_id=:workspaceId")
                .param("id", reportId).param("userId", actor.userId()).param("workspaceId", actor.workspaceId())
                .query(this::report).optional();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeliveryChannel> listChannels(ActorContext actor) {
        return jdbc.sql(channelSelect() + """
                where channel.user_id=:userId and channel.workspace_id=:workspaceId
                  and channel.deleted_at is null order by channel.created_at desc
                """).param("userId", actor.userId()).param("workspaceId", actor.workspaceId())
                .query(this::channel).list();
    }

    @Override
    @Transactional
    public DeliveryChannel createChannel(ActorContext actor, UUID channelId, String name,
                                         String endpointUrl, boolean enabled, Instant now) {
        String safeName = required(name, 100, "Channel name is required");
        var endpoint = WebhookUrlPolicy.syntax(endpointUrl);
        lockActor(actor);
        ensureChannelNameAvailable(actor, safeName, null);
        jdbc.sql("""
                insert into report_delivery_channel
                    (id,user_id,workspace_id,name,channel_type,endpoint_ciphertext,
                     endpoint_masked,enabled,created_at,updated_at)
                values (:id,:userId,:workspaceId,:name,'WEBHOOK',:ciphertext,:masked,:enabled,:now,:now)
                """).param("id", channelId).param("userId", actor.userId())
                .param("workspaceId", actor.workspaceId()).param("name", safeName)
                .param("ciphertext", cipher.encrypt(endpoint.toString()))
                .param("masked", WebhookUrlPolicy.masked(endpoint)).param("enabled", enabled)
                .param("now", timestamp(now)).update();
        return channelById(actor, channelId).orElseThrow();
    }

    @Override
    @Transactional
    public Optional<DeliveryChannel> updateChannel(ActorContext actor, UUID channelId, String name,
                                                   String endpointUrl, boolean enabled, Instant now) {
        String safeName = required(name, 100, "Channel name is required");
        lockActor(actor);
        ChannelSecret existing = channelSecretForUpdate(actor, channelId).orElse(null);
        if (existing == null) return Optional.empty();
        ensureChannelNameAvailable(actor, safeName, channelId);
        String ciphertext = existing.ciphertext();
        String masked = existing.masked();
        if (endpointUrl != null && !endpointUrl.isBlank()) {
            var endpoint = WebhookUrlPolicy.syntax(endpointUrl);
            ciphertext = cipher.encrypt(endpoint.toString());
            masked = WebhookUrlPolicy.masked(endpoint);
        }
        jdbc.sql("""
                update report_delivery_channel set name=:name,endpoint_ciphertext=:ciphertext,
                    endpoint_masked=:masked,enabled=:enabled,updated_at=:now where id=:id
                """).param("name", safeName).param("ciphertext", ciphertext).param("masked", masked)
                .param("enabled", enabled).param("now", timestamp(now)).param("id", channelId).update();
        return channelById(actor, channelId);
    }

    @Override
    @Transactional
    public boolean deleteChannel(ActorContext actor, UUID channelId, Instant now) {
        return jdbc.sql("""
                update report_delivery_channel set enabled=false,deleted_at=:now,updated_at=:now
                where id=:id and user_id=:userId and workspace_id=:workspaceId and deleted_at is null
                """).param("now", timestamp(now)).param("id", channelId)
                .param("userId", actor.userId()).param("workspaceId", actor.workspaceId()).update() == 1;
    }

    @Override
    @Transactional
    public Optional<DeliveryRecord> enqueueDelivery(ActorContext actor, UUID reportId,
                                                    UUID channelId, Instant now) {
        ChannelSecret channel = jdbc.sql("""
                select channel.id,channel.name,channel.channel_type,channel.endpoint_ciphertext,
                       channel.endpoint_masked,channel.enabled
                from report_delivery_channel channel
                where channel.id=:channelId and channel.user_id=:userId
                  and channel.workspace_id=:workspaceId and channel.deleted_at is null for update
                """).param("channelId", channelId).param("userId", actor.userId())
                .param("workspaceId", actor.workspaceId())
                .query((rs, row) -> new ChannelSecret(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("channel_type"), rs.getString("endpoint_ciphertext"),
                        rs.getString("endpoint_masked"), rs.getBoolean("enabled"))).optional().orElse(null);
        if (channel == null) return Optional.empty();
        if (!channel.enabled()) throw new IllegalStateException("Delivery channel is disabled");
        boolean reportExists = jdbc.sql("""
                select exists(select 1 from research_report
                    where id=:reportId and user_id=:userId and workspace_id=:workspaceId)
                """).param("reportId", reportId).param("userId", actor.userId())
                .param("workspaceId", actor.workspaceId()).query(Boolean.class).single();
        if (!reportExists) return Optional.empty();
        UUID deliveryId = UUID.randomUUID();
        jdbc.sql("""
                insert into report_delivery_job
                    (id,user_id,workspace_id,report_id,channel_id,channel_name,channel_type,
                     endpoint_ciphertext,endpoint_masked,status,next_attempt_at,created_at,updated_at)
                values (:id,:userId,:workspaceId,:reportId,:channelId,:channelName,:channelType,
                        :ciphertext,:masked,'PENDING',:now,:now,:now)
                on conflict (report_id,channel_id) do update set
                    channel_name=excluded.channel_name,channel_type=excluded.channel_type,
                    endpoint_ciphertext=excluded.endpoint_ciphertext,endpoint_masked=excluded.endpoint_masked,
                    status='PENDING',attempts=0,next_attempt_at=excluded.next_attempt_at,
                    lease_token=null,locked_until=null,response_code=null,duration_ms=null,
                    error_code=null,last_error=null,sent_at=null,updated_at=excluded.updated_at
                where report_delivery_job.status='FAILED'
                """).param("id", deliveryId).param("userId", actor.userId())
                .param("workspaceId", actor.workspaceId()).param("reportId", reportId)
                .param("channelId", channelId).param("channelName", channel.name())
                .param("channelType", channel.type()).param("ciphertext", channel.ciphertext())
                .param("masked", channel.masked()).param("now", timestamp(now)).update();
        return deliveryByReportChannel(actor, reportId, channelId);
    }

    @Override
    @Transactional(readOnly = true)
    public DeliveryPage listDeliveries(ActorContext actor, int page, int size, UUID reportId) {
        int safePage = Math.max(0, page), safeSize = Math.max(1, Math.min(100, size));
        String filter = reportId == null ? "" : " and delivery.report_id=:reportId";
        JdbcClient.StatementSpec count = jdbc.sql("""
                select count(*) from report_delivery_job delivery
                where delivery.user_id=:userId and delivery.workspace_id=:workspaceId
                """ + filter).param("userId", actor.userId()).param("workspaceId", actor.workspaceId());
        JdbcClient.StatementSpec query = jdbc.sql(deliverySelect() + """
                where delivery.user_id=:userId and delivery.workspace_id=:workspaceId
                """ + filter + " order by delivery.created_at desc limit :size offset :offset")
                .param("userId", actor.userId()).param("workspaceId", actor.workspaceId())
                .param("size", safeSize).param("offset", safePage * safeSize);
        if (reportId != null) { count = count.param("reportId", reportId); query = query.param("reportId", reportId); }
        return new DeliveryPage(query.query(this::delivery).list(), safePage, safeSize,
                count.query(Long.class).single());
    }

    @Override
    @Transactional
    public Optional<DeliveryRecord> retryDelivery(ActorContext actor, UUID deliveryId, Instant now) {
        int changed = jdbc.sql("""
                update report_delivery_job set status='PENDING',attempts=0,next_attempt_at=:now,
                    lease_token=null,locked_until=null,response_code=null,duration_ms=null,
                    error_code=null,last_error=null,sent_at=null,updated_at=:now
                where id=:id and user_id=:userId and workspace_id=:workspaceId and status='FAILED'
                """).param("now", timestamp(now)).param("id", deliveryId)
                .param("userId", actor.userId()).param("workspaceId", actor.workspaceId()).update();
        return changed == 0 ? Optional.empty() : deliveryById(actor, deliveryId);
    }

    @Override
    @Transactional
    public List<DeliveryTask> claimDueDeliveries(Instant now, Duration leaseDuration, int limit) {
        List<UUID> ids = jdbc.sql("""
                with due as (
                    select id from report_delivery_job
                    where ((status in ('PENDING','RETRY_WAIT') and next_attempt_at<=:now)
                        or (status='RUNNING' and locked_until<=:now))
                    order by next_attempt_at,created_at for update skip locked limit :limit
                )
                update report_delivery_job delivery set status='RUNNING',attempts=attempts+1,
                    lease_token=gen_random_uuid(),locked_until=:lockedUntil,last_error=null,updated_at=:now
                from due where delivery.id=due.id returning delivery.id
                """).param("now", timestamp(now)).param("lockedUntil", timestamp(now.plus(leaseDuration)))
                .param("limit", Math.max(1, Math.min(50, limit))).query(UUID.class).list();
        List<DeliveryTask> tasks = new ArrayList<>();
        for (UUID id : ids) tasks.add(loadTask(id));
        return List.copyOf(tasks);
    }

    @Override
    @Transactional
    public void completeDelivery(UUID deliveryId, UUID leaseToken, int responseCode,
                                 long durationMs, Instant completedAt) {
        int changed = jdbc.sql("""
                update report_delivery_job set status='SUCCEEDED',response_code=:responseCode,
                    duration_ms=:durationMs,sent_at=:now,lease_token=null,locked_until=null,
                    error_code=null,last_error=null,updated_at=:now
                where id=:id and status='RUNNING' and lease_token=:leaseToken
                """).param("responseCode", responseCode).param("durationMs", Math.max(0, durationMs))
                .param("now", timestamp(completedAt)).param("id", deliveryId)
                .param("leaseToken", leaseToken).update();
        if (changed != 1) throw new IllegalStateException("Report delivery lease was lost");
    }

    @Override
    @Transactional
    public void failDelivery(UUID deliveryId, UUID leaseToken, String errorCode, String errorMessage,
                             Integer responseCode, long durationMs, Instant failedAt,
                             Instant nextAttemptAt, boolean terminal) {
        int changed = jdbc.sql("""
                update report_delivery_job set status=:status,response_code=:responseCode,
                    duration_ms=:durationMs,error_code=:errorCode,last_error=:lastError,
                    next_attempt_at=:nextAttemptAt,lease_token=null,locked_until=null,updated_at=:now
                where id=:id and status='RUNNING' and lease_token=:leaseToken
                """).param("status", terminal ? "FAILED" : "RETRY_WAIT")
                .param("responseCode", responseCode).param("durationMs", Math.max(0, durationMs))
                .param("errorCode", clean(errorCode, 64)).param("lastError", clean(errorMessage, 1000))
                .param("nextAttemptAt", timestamp(nextAttemptAt)).param("now", timestamp(failedAt))
                .param("id", deliveryId).param("leaseToken", leaseToken).update();
        if (changed != 1) throw new IllegalStateException("Report delivery lease was lost");
    }

    private DeliveryTask loadTask(UUID id) {
        return jdbc.sql("""
                select delivery.id,delivery.lease_token,delivery.report_id,report.title,
                       report.period_start,report.period_end,report.item_count,report.high_risk_count,
                       left(report.markdown_content,4000) as markdown_excerpt,
                       delivery.endpoint_ciphertext,delivery.attempts,delivery.max_attempts
                from report_delivery_job delivery join research_report report on report.id=delivery.report_id
                where delivery.id=:id and delivery.status='RUNNING'
                """).param("id", id).query((rs, row) -> new DeliveryTask(
                rs.getObject("id", UUID.class), rs.getObject("lease_token", UUID.class),
                rs.getObject("report_id", UUID.class), rs.getString("title"),
                instant(rs, "period_start"), instant(rs, "period_end"), rs.getInt("item_count"),
                rs.getInt("high_risk_count"), rs.getString("markdown_excerpt"),
                cipher.decrypt(rs.getString("endpoint_ciphertext")), rs.getInt("attempts"),
                rs.getInt("max_attempts"))).single();
    }

    private Optional<DeliveryChannel> channelById(ActorContext actor, UUID id) {
        return jdbc.sql(channelSelect() + """
                where channel.id=:id and channel.user_id=:userId and channel.workspace_id=:workspaceId
                  and channel.deleted_at is null
                """).param("id", id).param("userId", actor.userId())
                .param("workspaceId", actor.workspaceId()).query(this::channel).optional();
    }

    private Optional<ChannelSecret> channelSecretForUpdate(ActorContext actor, UUID id) {
        return jdbc.sql("""
                select id,name,channel_type,endpoint_ciphertext,endpoint_masked,enabled
                from report_delivery_channel where id=:id and user_id=:userId
                  and workspace_id=:workspaceId and deleted_at is null for update
                """).param("id", id).param("userId", actor.userId()).param("workspaceId", actor.workspaceId())
                .query((rs, row) -> new ChannelSecret(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("channel_type"), rs.getString("endpoint_ciphertext"),
                        rs.getString("endpoint_masked"), rs.getBoolean("enabled"))).optional();
    }

    private void ensureChannelNameAvailable(ActorContext actor, String name, UUID excludedId) {
        boolean exists = jdbc.sql("""
                select exists(select 1 from report_delivery_channel
                    where user_id=:userId and workspace_id=:workspaceId and deleted_at is null
                      and lower(name)=lower(:name)
                      and (cast(:excludedId as uuid) is null or id<>cast(:excludedId as uuid)))
                """).param("userId", actor.userId()).param("workspaceId", actor.workspaceId())
                .param("name", name).param("excludedId", excludedId).query(Boolean.class).single();
        if (exists) throw new IllegalArgumentException("Channel name already exists");
    }

    private void lockActor(ActorContext actor) {
        jdbc.sql("select id from app_user where id=:userId for update")
                .param("userId", actor.userId()).query(UUID.class).single();
    }

    private Optional<DeliveryRecord> deliveryByReportChannel(ActorContext actor, UUID reportId, UUID channelId) {
        return jdbc.sql(deliverySelect() + """
                where delivery.report_id=:reportId and delivery.channel_id=:channelId
                  and delivery.user_id=:userId and delivery.workspace_id=:workspaceId
                """).param("reportId", reportId).param("channelId", channelId)
                .param("userId", actor.userId()).param("workspaceId", actor.workspaceId())
                .query(this::delivery).optional();
    }

    private Optional<DeliveryRecord> deliveryById(ActorContext actor, UUID id) {
        return jdbc.sql(deliverySelect() + """
                where delivery.id=:id and delivery.user_id=:userId and delivery.workspace_id=:workspaceId
                """).param("id", id).param("userId", actor.userId()).param("workspaceId", actor.workspaceId())
                .query(this::delivery).optional();
    }

    private ReportItem reportItem(ResultSet rs, int row) throws SQLException {
        return new ReportItem(rs.getObject("analysis_id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getString("project_name"), rs.getString("event_type"), rs.getString("version_tag"),
                rs.getString("event_title"), rs.getString("source_url"), rs.getString("risk_level"),
                rs.getString("recommendation"), rs.getString("evidence_status"), rs.getString("one_line_summary"),
                strings(rs.getString("major_changes")), rs.getString("java_impact"), rs.getString("upgrade_value"),
                strings(rs.getString("risks")), strings(rs.getString("recommended_actions")),
                strings(rs.getString("evidence_urls")), instant(rs, "occurred_at"), instant(rs, "completed_at"));
    }

    private ReportRecord report(ResultSet rs, int row) throws SQLException {
        return new ReportRecord(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("report_type"),
                instant(rs, "period_start"), instant(rs, "period_end"), uuidArray(rs.getArray("project_ids")),
                stringArray(rs.getArray("event_types")), rs.getInt("item_count"), rs.getInt("high_risk_count"),
                readItems(rs.getString("snapshot")), rs.getString("markdown_content"), instant(rs, "created_at"));
    }

    private DeliveryChannel channel(ResultSet rs, int row) throws SQLException {
        return new DeliveryChannel(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("channel_type"), rs.getString("endpoint_masked"), rs.getBoolean("enabled"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private DeliveryRecord delivery(ResultSet rs, int row) throws SQLException {
        return new DeliveryRecord(rs.getObject("id", UUID.class), rs.getObject("report_id", UUID.class),
                rs.getString("report_title"), rs.getObject("channel_id", UUID.class), rs.getString("channel_name"),
                rs.getString("channel_type"), rs.getString("endpoint_masked"), rs.getString("status"),
                rs.getInt("attempts"), rs.getInt("max_attempts"), (Integer) rs.getObject("response_code"),
                (Long) rs.getObject("duration_ms"), rs.getString("last_error"), instant(rs, "next_attempt_at"),
                instant(rs, "sent_at"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private List<ReportItem> readItems(String value) {
        try { return value == null ? List.of() : json.readValue(value, ITEM_LIST); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Unable to read report snapshot", exception); }
    }

    private List<String> strings(String value) {
        try { return value == null ? List.of() : json.readValue(value, new TypeReference<>() { }); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Unable to read report item list", exception); }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Unable to serialize report", exception); }
    }

    private static List<UUID> uuidArray(Array array) throws SQLException {
        if (array == null) return List.of();
        Object[] values = (Object[]) array.getArray();
        List<UUID> result = new ArrayList<>(values.length);
        for (Object value : values) result.add(value instanceof UUID uuid ? uuid : UUID.fromString(value.toString()));
        return List.copyOf(result);
    }

    private static List<String> stringArray(Array array) throws SQLException {
        if (array == null) return List.of();
        Object[] values = (Object[]) array.getArray();
        List<String> result = new ArrayList<>(values.length);
        for (Object value : values) result.add(value.toString());
        return List.copyOf(result);
    }

    private static List<UUID> distinct(List<UUID> values) {
        return values == null ? List.of() : List.copyOf(new LinkedHashSet<>(values));
    }

    private static List<String> normalizedEventTypes(List<String> values) {
        if (values == null) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
            if (!EVENT_TYPES.contains(normalized)) throw new IllegalArgumentException("Unsupported report event type");
            result.add(normalized);
        }
        return List.copyOf(result);
    }

    private static String required(String value, int max, String message) {
        String safe = clean(value, max);
        if (safe == null || safe.isBlank()) throw new IllegalArgumentException(message);
        return safe;
    }

    private static String clean(String value, int max) {
        if (value == null) return null;
        String safe = value.replace("\u0000", "").trim();
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    private static OffsetDateTime timestamp(Instant value) { return value.atOffset(ZoneOffset.UTC); }
    private static Instant instant(ResultSet rs, String name) throws SQLException {
        OffsetDateTime value = rs.getObject(name, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static String reportSelect() { return "select report.* from research_report report "; }
    private static String channelSelect() { return "select channel.* from report_delivery_channel channel "; }
    private static String deliverySelect() {
        return """
                select delivery.*,report.title as report_title from report_delivery_job delivery
                join research_report report on report.id=delivery.report_id
                """;
    }

    private record ChannelSecret(UUID id, String name, String type, String ciphertext,
                                 String masked, boolean enabled) { }
}
