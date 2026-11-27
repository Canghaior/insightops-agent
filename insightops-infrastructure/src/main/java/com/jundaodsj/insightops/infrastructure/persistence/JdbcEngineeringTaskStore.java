package com.jundaodsj.insightops.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.report.application.EngineeringTaskStore;
import com.jundaodsj.insightops.report.application.ReportReviewStore.Author;
import com.jundaodsj.insightops.report.application.ReportReviewStore.Context;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcEngineeringTaskStore implements EngineeringTaskStore {
    private static final String VERSIONS = """
            select t.*, t.fields || jsonb_build_object('verificationVersionIds',
              (select coalesce(jsonb_agg(b.verification_version_id order by b.verification_version_id), '[]'::jsonb)
               from report_engineering_task_basis b where b.task_version_id=t.id and b.report_id=t.report_id)) as task_fields
            from report_engineering_task_version t where t.report_id=:report
            """;
    private static final String RESULTS = """
            select task_version_id,verification_version_id,executed_at,source_revision,candidate_revision,unfinished,
                   patch_name,patch_size,patch_sha256,recorder_id,recorder_name,created_at
            from report_engineering_task_result where report_id=:report
            """;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcEngineeringTaskStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<TaskVersion> versions(UUID reportId) {
        return jdbc.sql(VERSIONS + " order by t.created_at,t.id").param("report", reportId)
                .query(this::readVersion).list();
    }

    @Override
    public Optional<TaskVersion> version(UUID reportId, UUID versionId) {
        return jdbc.sql(VERSIONS + " and t.id=:id").param("report", reportId).param("id", versionId)
                .query(this::readVersion).optional();
    }

    @Override
    public Optional<TaskVersion> versionRequest(UUID reportId, UUID requestId) {
        return jdbc.sql(VERSIONS + " and t.request_id=:key").param("report", reportId).param("key", requestId)
                .query(this::readVersion).optional();
    }

    @Override
    public boolean hasRevision(UUID reportId, UUID previousVersionId) {
        return jdbc.sql("select exists (select 1 from report_engineering_task_version where report_id=? and previous_version_id=?)")
                .params(reportId, previousVersionId).query(Boolean.class).single();
    }

    @Override
    public void insert(Context context, ActorContext actor, UUID versionId, UUID taskId, UUID requestId,
                       UUID previousVersionId, int version, UUID baselineId, Integer baselineVersion,
                       int excerptLine, TaskFields fields) {
        String encoded;
        try {
            encoded = json.writeValueAsString(fields);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("任务内容无法序列化。", e);
        }
        jdbc.sql("""
                insert into report_engineering_task_version
                  (id,report_id,task_id,previous_version_id,version,request_id,workspace_id,author_id,author_name,
                   source_run_id,system_id,system_revision,engineering_baseline_id,engineering_baseline_version,excerpt_line,fields)
                select :id,:report,:task,:previous,:ver,:key,:workspace,u.id,u.display_name,:run,:system,:revision,
                       :baseline,:baselineVersion,:line,cast(:fields as jsonb)-'verificationVersionIds'
                from app_user u where u.id=:user
                """).param("id", versionId).param("report", context.reportId()).param("task", taskId)
                .param("previous", previousVersionId).param("ver", version).param("key", requestId)
                .param("workspace", actor.workspaceId()).param("user", actor.userId()).param("run", context.runId())
                .param("system", context.systemId()).param("revision", context.systemRevision())
                .param("baseline", baselineId).param("baselineVersion", baselineVersion)
                .param("line", excerptLine).param("fields", encoded).update();
        if (!fields.verificationVersionIds().isEmpty()) {
            int inserted = jdbc.sql("""
                    insert into report_engineering_task_basis(report_id,task_version_id,verification_version_id)
                    select report_id,:task,id from report_verification where report_id=:report and id in (:versions)
                    """).param("report", context.reportId()).param("task", versionId)
                    .param("versions", fields.verificationVersionIds()).update();
            if (inserted != fields.verificationVersionIds().size()) {
                throw new IllegalArgumentException("所选验证依据必须是本报告已保存的具体版本。");
            }
        }
    }

    @Override
    public List<TaskResult> results(UUID reportId) {
        return jdbc.sql(RESULTS + " order by created_at,verification_version_id").param("report", reportId)
                .query(JdbcEngineeringTaskStore::readResult).list();
    }

    @Override
    public Optional<TaskResult> result(UUID reportId, UUID verificationVersionId) {
        return jdbc.sql(RESULTS + " and verification_version_id=:version").param("report", reportId)
                .param("version", verificationVersionId).query(JdbcEngineeringTaskStore::readResult).optional();
    }

    @Override
    public Optional<TaskResult> resultRequest(UUID reportId, UUID requestId) {
        return jdbc.sql(RESULTS + " and request_id=:key").param("report", reportId).param("key", requestId)
                .query(JdbcEngineeringTaskStore::readResult).optional();
    }

    @Override
    public Optional<byte[]> patchBytes(UUID reportId, UUID verificationVersionId) {
        return jdbc.sql("""
                select patch_bytes from report_engineering_task_result
                where report_id=? and verification_version_id=? and patch_bytes is not null
                """).params(reportId, verificationVersionId).query((rs,n) -> rs.getBytes(1)).optional();
    }

    @Override
    public void addResult(UUID reportId, UUID taskVersionId, UUID verificationVersionId, UUID requestId, ActorContext actor,
                          Instant executedAt, String sourceRevision, String candidateRevision, String unfinished,
                          PatchInfo patch, byte[] bytes) {
        jdbc.sql("""
                insert into report_engineering_task_result
                 (report_id,task_version_id,verification_version_id,request_id,recorder_id,recorder_name,
                  executed_at,source_revision,candidate_revision,unfinished,patch_name,patch_size,patch_sha256,patch_bytes)
                select :report,:task,:verification,:key,u.id,u.display_name,:executed,:source,:candidate,:unfinished,
                       :name,:size,:hash,:bytes from app_user u where u.id=:user
                """).param("report", reportId).param("task", taskVersionId).param("verification", verificationVersionId)
                .param("key", requestId).param("user", actor.userId())
                .param("executed", executedAt == null ? null : Timestamp.from(executedAt))
                .param("source", sourceRevision).param("candidate", candidateRevision).param("unfinished", unfinished)
                .param("name", patch == null ? null : patch.name()).param("size", patch == null ? null : patch.size())
                .param("hash", patch == null ? null : patch.sha256()).param("bytes", bytes, java.sql.Types.BINARY).update();
    }

    private TaskVersion readVersion(ResultSet r, int index) throws SQLException {
        TaskFields fields;
        try {
            fields = json.readValue(r.getString("task_fields"), TaskFields.class);
        } catch (JsonProcessingException e) {
            throw new SQLException("Stored task fields are unreadable", e);
        }
        return new TaskVersion(id(r, "id"), id(r, "task_id"), id(r, "previous_version_id"), r.getInt("version"),
                fields, id(r, "report_id"), id(r, "source_run_id"), id(r, "system_id"), r.getInt("system_revision"),
                id(r, "engineering_baseline_id"), r.getObject("engineering_baseline_version", Integer.class),
                r.getInt("excerpt_line"), new Author(id(r, "author_id"), r.getString("author_name")),
                r.getTimestamp("created_at").toInstant());
    }

    private static TaskResult readResult(ResultSet r, int index) throws SQLException {
        String name = r.getString("patch_name");
        PatchInfo patch = name == null ? null : new PatchInfo(name, r.getInt("patch_size"), r.getString("patch_sha256"));
        Timestamp executedAt = r.getTimestamp("executed_at");
        return new TaskResult(id(r, "task_version_id"), id(r, "verification_version_id"),
                executedAt == null ? null : executedAt.toInstant(), r.getString("source_revision"),
                r.getString("candidate_revision"), r.getString("unfinished"), patch,
                new Author(id(r, "recorder_id"), r.getString("recorder_name")), r.getTimestamp("created_at").toInstant());
    }

    private static UUID id(ResultSet r, String column) throws SQLException {
        return r.getObject(column, UUID.class);
    }
}
