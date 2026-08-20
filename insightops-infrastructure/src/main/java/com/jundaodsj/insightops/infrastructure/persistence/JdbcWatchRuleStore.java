package com.jundaodsj.insightops.infrastructure.persistence;

import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.intelligence.application.WatchRuleStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class JdbcWatchRuleStore implements WatchRuleStore {

    private static final Set<String> EVENT_TYPES = Set.of(
            "GITHUB_RELEASE", "GITHUB_ISSUE", "GITHUB_PULL_REQUEST",
            "GITHUB_SECURITY_ADVISORY");
    private final JdbcClient jdbc;

    public JdbcWatchRuleStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<WatchRule> list(ActorContext actor) {
        return jdbc.sql("""
                select rule.*, project.repository_name,
                       (select count(*) from event_rule_match rule_match where rule_match.rule_id=rule.id) as match_count
                from user_watch_rule rule
                left join tracked_project project on project.id=rule.project_id
                where rule.user_id=:userId and rule.workspace_id=:workspaceId
                order by rule.enabled desc, rule.updated_at desc
                """)
                .param("userId", actor.userId()).param("workspaceId", actor.workspaceId())
                .query(this::map).list();
    }

    @Override
    public WatchRule create(ActorContext actor, RuleCommand command, Instant now) {
        RuleCommand safe = validate(actor, command);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into user_watch_rule
                    (id,user_id,workspace_id,project_id,name,keywords,excluded_keywords,event_types,
                     minimum_importance,immediate_notification,include_in_digest,enabled,created_at,updated_at)
                values (:id,:userId,:workspaceId,:projectId,:name,:keywords,:excluded,:eventTypes,
                        :importance,:immediate,:digest,:enabled,:now,:now)
                """)
                .param("id", id).param("userId", actor.userId())
                .param("workspaceId", actor.workspaceId()).param("projectId", safe.projectId())
                .param("name", safe.name()).param("keywords", array(safe.keywords()))
                .param("excluded", array(safe.excludedKeywords())).param("eventTypes", array(safe.eventTypes()))
                .param("importance", safe.minimumImportance()).param("immediate", safe.immediateNotification())
                .param("digest", safe.includeInDigest()).param("enabled", safe.enabled())
                .param("now", timestamp(now)).update();
        return find(actor, id).orElseThrow();
    }

    @Override
    public Optional<WatchRule> update(ActorContext actor, UUID ruleId, RuleCommand command, Instant now) {
        RuleCommand safe = validate(actor, command);
        int updated = jdbc.sql("""
                update user_watch_rule set project_id=:projectId,name=:name,keywords=:keywords,
                    excluded_keywords=:excluded,event_types=:eventTypes,minimum_importance=:importance,
                    immediate_notification=:immediate,include_in_digest=:digest,enabled=:enabled,updated_at=:now
                where id=:id and user_id=:userId and workspace_id=:workspaceId
                """)
                .param("projectId", safe.projectId()).param("name", safe.name())
                .param("keywords", array(safe.keywords())).param("excluded", array(safe.excludedKeywords()))
                .param("eventTypes", array(safe.eventTypes())).param("importance", safe.minimumImportance())
                .param("immediate", safe.immediateNotification()).param("digest", safe.includeInDigest())
                .param("enabled", safe.enabled()).param("now", timestamp(now)).param("id", ruleId)
                .param("userId", actor.userId()).param("workspaceId", actor.workspaceId()).update();
        return updated == 0 ? Optional.empty() : find(actor, ruleId);
    }

    @Override
    public boolean delete(ActorContext actor, UUID ruleId) {
        return jdbc.sql("delete from user_watch_rule where id=:id and user_id=:userId and workspace_id=:workspaceId")
                .param("id", ruleId).param("userId", actor.userId())
                .param("workspaceId", actor.workspaceId()).update() == 1;
    }

    private Optional<WatchRule> find(ActorContext actor, UUID id) {
        return jdbc.sql("""
                select rule.*, project.repository_name,
                       (select count(*) from event_rule_match rule_match where rule_match.rule_id=rule.id) as match_count
                from user_watch_rule rule left join tracked_project project on project.id=rule.project_id
                where rule.id=:id and rule.user_id=:userId and rule.workspace_id=:workspaceId
                """)
                .param("id", id).param("userId", actor.userId()).param("workspaceId", actor.workspaceId())
                .query(this::map).optional();
    }

    private RuleCommand validate(ActorContext actor, RuleCommand command) {
        if (command == null) throw new IllegalArgumentException("Rule body is required");
        String name = clean(command.name(), 128);
        if (name.isBlank()) throw new IllegalArgumentException("Rule name is required");
        List<String> keywords = cleanList(command.keywords(), 20, 80, false);
        List<String> excluded = cleanList(command.excludedKeywords(), 20, 80, false);
        List<String> types = cleanList(command.eventTypes(), 4, 48, true);
        if (!EVENT_TYPES.containsAll(types)) throw new IllegalArgumentException("Unsupported event type");
        if (command.projectId() != null) {
            long allowed = jdbc.sql("""
                    select count(*) from tracked_project project
                    join user_project_watch watch on watch.project_id=project.id and watch.user_id=:userId
                    where project.id=:projectId and project.workspace_id=:workspaceId and watch.enabled=true
                    """).param("userId", actor.userId()).param("projectId", command.projectId())
                    .param("workspaceId", actor.workspaceId()).query(Long.class).single();
            if (allowed != 1) throw new IllegalArgumentException("Project is not watched by the current user");
        }
        if (keywords.isEmpty() && types.isEmpty() && command.projectId() == null) {
            throw new IllegalArgumentException("Rule must contain a project, keyword, or event type");
        }
        int importance = Math.max(1, Math.min(command.minimumImportance(), 5));
        return new RuleCommand(name, command.projectId(), keywords, excluded, types, importance,
                command.immediateNotification(), command.includeInDigest(), command.enabled());
    }

    private WatchRule map(ResultSet rs, int rowNum) throws SQLException {
        return new WatchRule(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getString("repository_name"), rs.getString("name"), list(rs, "keywords"),
                list(rs, "excluded_keywords"), list(rs, "event_types"),
                rs.getInt("minimum_importance"), rs.getBoolean("immediate_notification"),
                rs.getBoolean("include_in_digest"), rs.getBoolean("enabled"), rs.getLong("match_count"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static List<String> cleanList(List<String> values, int maxItems, int maxLength, boolean uppercase) {
        if (values == null) return List.of();
        return values.stream().map(value -> clean(value, maxLength))
                .filter(value -> !value.isBlank()).map(value -> uppercase ? value.toUpperCase(Locale.ROOT) : value)
                .distinct().limit(maxItems).toList();
    }

    private static String clean(String value, int maxLength) {
        String safe = value == null ? "" : value.replace("\u0000", "").trim();
        return safe.length() <= maxLength ? safe : safe.substring(0, maxLength);
    }

    private static String[] array(List<String> values) { return values.toArray(String[]::new); }
    private static OffsetDateTime timestamp(Instant value) { return OffsetDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value=rs.getObject(column,OffsetDateTime.class); return value==null?null:value.toInstant();
    }
    private static List<String> list(ResultSet rs, String column) throws SQLException {
        Array value=rs.getArray(column); if(value==null)return List.of();
        Object raw=value.getArray(); return raw instanceof Object[] values
                ? Arrays.stream(values).map(String::valueOf).toList() : List.of();
    }
}
