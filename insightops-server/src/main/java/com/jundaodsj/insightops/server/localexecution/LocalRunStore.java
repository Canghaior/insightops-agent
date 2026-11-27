package com.jundaodsj.insightops.server.localexecution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.identity.application.ActorContext;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class LocalRunStore {
    enum Status { QUEUED, ANALYZING, BASELINE, EDITING, TESTING, SUCCEEDED, FAILED, BLOCKED, CANCELLED, INTERRUPTED }

    record Command(UUID requestId, String workspace, String sourceRevision, String modelChoice,
                   String instruction, List<String> writeScopes,
                   @JsonInclude(JsonInclude.Include.NON_NULL) LocalBuildPlan buildPlan) {
        LocalBuildPlan effectivePlan() { return buildPlan == null ? LocalBuildPlan.ALL : buildPlan; }
    }

    record Run(UUID id, UUID reportId, UUID taskVersionId, UUID actorId, UUID workspaceId, UUID baselineId,
               int baselineVersion, String pomSha256, Command input, Status status, Instant createdAt, Instant updatedAt,
               Instant finishedAt, boolean cancelRequested, String note, UUID verificationId, String patchSha256) {
        boolean finished() { return finishedAt != null; }
    }

    record Event(long sequence, String type, String message, Map<String, Object> details, Instant createdAt) {}
    record Detail(Run run, List<Event> events) {}

    private static final String SCOPE = "report_id = :report AND task_version_id = :task AND actor_id = :actor AND workspace_id = :workspace";
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final RowMapper<Run> mapper = (r, row) -> new Run(uuid(r, "id"), uuid(r, "report_id"),
            uuid(r, "task_version_id"), uuid(r, "actor_id"), uuid(r, "workspace_id"), uuid(r, "baseline_id"),
            r.getInt("baseline_version"), r.getString("pom_sha256"), read(r.getString("input"), Command.class),
            Status.valueOf(r.getString("status")), instant(r, "created_at"), instant(r, "updated_at"),
            instant(r, "finished_at"), r.getBoolean("cancel_requested"), r.getString("note"),
            uuid(r, "verification_id"), r.getString("patch_sha256"));

    LocalRunStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    boolean insert(UUID id, ActorContext actor, UUID report, UUID task, UUID baseline, int baselineVersion,
                   String pomSha, Command input) {
        return jdbc.sql("""
                INSERT INTO local_engineering_run (id, report_id, task_version_id, actor_id, workspace_id, request_id,
                    baseline_id, baseline_version, pom_sha256, input, status)
                VALUES (:id, :report, :task, :actor, :workspace, :request, :baseline, :version, :pom,
                    cast(:input as jsonb), 'QUEUED') ON CONFLICT DO NOTHING
                """).param("id", id).param("report", report).param("task", task).param("actor", actor.userId())
                .param("workspace", actor.workspaceId()).param("request", input.requestId()).param("baseline", baseline)
                .param("version", baselineVersion).param("pom", pomSha).param("input", write(input)).update() == 1;
    }

    List<Run> list(ActorContext actor, UUID report, UUID task) {
        return scoped(" ORDER BY created_at DESC LIMIT 30", actor, report, task).query(mapper).list();
    }

    Optional<Run> request(ActorContext actor, UUID report, UUID task, UUID requestId) {
        return scoped(" AND request_id = :request", actor, report, task).param("request", requestId).query(mapper).optional();
    }

    Optional<Run> find(ActorContext actor, UUID report, UUID task, UUID id) {
        return scoped(" AND id = :id", actor, report, task).param("id", id).query(mapper).optional();
    }

    private JdbcClient.StatementSpec scoped(String suffix, ActorContext actor, UUID report, UUID task) {
        return jdbc.sql("SELECT * FROM local_engineering_run WHERE " + SCOPE + suffix).param("report", report)
                .param("task", task).param("actor", actor.userId()).param("workspace", actor.workspaceId());
    }

    List<UUID> active() {
        return jdbc.sql("SELECT id FROM local_engineering_run WHERE finished_at IS NULL").query(UUID.class).list();
    }

    void stage(UUID id, Status status, String note) {
        jdbc.sql("UPDATE local_engineering_run SET status = :status, note = :note, updated_at = clock_timestamp() " +
                        "WHERE id = :id AND finished_at IS NULL")
                .param("status", status.name()).param("note", note).param("id", id).update();
    }

    boolean cancel(UUID id) {
        return jdbc.sql("UPDATE local_engineering_run SET cancel_requested = TRUE, updated_at = clock_timestamp() " +
                        "WHERE id = :id AND finished_at IS NULL AND NOT cancel_requested").param("id", id).update() == 1;
    }

    boolean finish(UUID id, Status status, String note, UUID verification, String patch, boolean honorCancellation) {
        return jdbc.sql("""
                UPDATE local_engineering_run SET status = :status, note = :note, finished_at = clock_timestamp(),
                    updated_at = clock_timestamp(), verification_id = :verification, patch_sha256 = :patch
                WHERE id = :id AND finished_at IS NULL AND (NOT :honor OR NOT cancel_requested)
                """).param("status", status.name()).param("note", note).param("verification", verification)
                .param("patch", patch).param("id", id).param("honor", honorCancellation).update() == 1;
    }

    void event(UUID id, String type, String message, Object details) {
        jdbc.sql("""
                INSERT INTO local_engineering_event (run_id, event_type, message, details)
                    VALUES (:id, :type, :message, cast(:details as jsonb))
                """).param("id", id).param("type", type).param("message", message).param("details", write(details)).update();
    }

    List<Event> events(UUID id) {
        return jdbc.sql("SELECT * FROM local_engineering_event WHERE run_id = :id ORDER BY sequence")
                .param("id", id).query((r, row) -> new Event(r.getLong("sequence"), r.getString("event_type"),
                        r.getString("message"), readDetails(r.getString("details")), instant(r, "created_at"))).list();
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("无法保存本地执行记录。", e); }
    }

    private <T> T read(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException e) { throw new IllegalStateException("无法读取本地执行记录。", e); }
    }

    private Map<String, Object> readDetails(String value) {
        try { return json.readValue(value, new TypeReference<>() {}); }
        catch (JsonProcessingException e) { throw new IllegalStateException("无法读取本地工具和模型记录。", e); }
    }

    private static UUID uuid(ResultSet row, String field) throws SQLException { return row.getObject(field, UUID.class); }

    private static Instant instant(ResultSet row, String field) throws SQLException {
        OffsetDateTime time = row.getObject(field, OffsetDateTime.class);
        return time == null ? null : time.toInstant();
    }
}
