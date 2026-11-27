package com.jundaodsj.insightops.infrastructure.persistence;

import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.report.application.ReportReviewStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcReportReviewStore implements ReportReviewStore {
    private final JdbcClient jdbc;

    public JdbcReportReviewStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<Context> context(ActorContext actor, UUID reportId, boolean lock) {
        // Always anchor to the archived revision. The live system supplies only identity and ownership.
        String sql = """
                select r.id, w.run_id, s.id as system_id, r.research_snapshot #>> '{inputs,systemSnapshot,revision}' as revision,
                       coalesce(nullif(r.research_snapshot->>'requestedModel',''), a.requested_model) as requested,
                       coalesce(nullif(r.research_snapshot->>'responseModel',''), a.model_name) as response, a.model_provider
                from research_report r
                join agent_run a on a.id=r.source_run_id and a.workspace_id=r.workspace_id and a.owner_user_id=r.user_id
                join agent_workflow_run w on w.run_id=a.id and w.workspace_id=r.workspace_id and w.owner_user_id=r.user_id
                join business_system s on s.id=w.business_system_id and s.workspace_id=r.workspace_id and s.owner_user_id=r.user_id
                where r.id=:id and r.workspace_id=:workspace and r.user_id=:user and r.report_type='RESEARCH'
                  and r.research_snapshot->>'runId'=w.run_id::text
                  and (
                    (r.research_snapshot #>> '{inputs,assessmentType}'='SYSTEM_DECISION'
                        and w.input_snapshot->>'assessmentType'='SYSTEM_DECISION')
                    or (
                      w.template_name_snapshot='升级评估（版本范围）'
                      and (
                        (r.research_snapshot #>> '{inputs,assessmentType}'='UPGRADE_ASSESSMENT'
                          and w.input_snapshot->>'assessmentType'='UPGRADE_ASSESSMENT')
                        or (not jsonb_exists(r.research_snapshot->'inputs','assessmentType')
                          and not jsonb_exists(w.input_snapshot,'assessmentType'))
                      )
                      and w.input_snapshot->>'projectId' ~ '^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$'
                      and r.research_snapshot #> '{inputs,projectId}'=w.input_snapshot->'projectId'
                      and jsonb_typeof(w.input_snapshot->'currentVersion')='string'
                      and jsonb_typeof(w.input_snapshot->'targetVersion')='string'
                      and nullif(trim(w.input_snapshot->>'currentVersion'),'') is not null
                      and nullif(trim(w.input_snapshot->>'targetVersion'),'') is not null
                      and r.research_snapshot #> '{inputs,currentVersion}'=w.input_snapshot->'currentVersion'
                      and r.research_snapshot #> '{inputs,targetVersion}'=w.input_snapshot->'targetVersion'
                    )
                  )
                  and r.research_snapshot #>> '{inputs,systemSnapshot,id}'=s.id::text
                  and r.research_snapshot #> '{inputs,systemSnapshot}'=w.input_snapshot->'systemSnapshot'
                  and r.research_snapshot #>> '{inputs,systemSnapshot,revision}' ~ '^[1-9][0-9]*$'
                """;
        return jdbc.sql(sql + (lock ? " for update of r" : "")).param("id", reportId)
                .param("workspace", actor.workspaceId()).param("user", actor.userId())
                .query((rs, n) -> new Context(reportId, id(rs, "run_id"), id(rs, "system_id"), rs.getInt("revision"),
                        rs.getString("requested"), rs.getString("response"), rs.getString("model_provider"))).optional();
    }

    private static final String VERIFICATIONS = """
            select v.*, u.display_name from report_verification v join app_user u on u.id=v.author_id
            where v.report_id=:report
            """;
    private static final String DECISIONS = """
            select d.*, u.display_name, array(select b.verification_version_id from report_decision_basis b
                where b.decision_id=d.id and b.report_id=d.report_id order by b.verification_version_id) as basis
            from report_human_decision d join app_user u on u.id=d.author_id where d.report_id=:report
            """;

    @Override public List<Verification> verifications(UUID reportId) {
        return jdbc.sql(VERIFICATIONS + " order by v.created_at, v.id").param("report", reportId)
                .query((rs, n) -> verification(rs)).list();
    }

    @Override public List<Decision> decisions(UUID reportId) {
        return jdbc.sql(DECISIONS + " order by d.created_at, d.id").param("report", reportId)
                .query((rs, n) -> decision(rs)).list();
    }

    @Override public Optional<Verification> verificationRequest(UUID reportId, UUID requestId) {
        return jdbc.sql(VERIFICATIONS + " and v.request_id=:request").param("report", reportId).param("request", requestId)
                .query((rs, n) -> verification(rs)).optional();
    }

    @Override public Optional<Decision> decisionRequest(UUID reportId, UUID requestId) {
        return jdbc.sql(DECISIONS + " and d.request_id=:request").param("report", reportId).param("request", requestId)
                .query((rs, n) -> decision(rs)).optional();
    }

    @Override public Optional<Verification> verification(UUID reportId, UUID versionId) {
        return jdbc.sql(VERIFICATIONS + " and v.id=:id").param("report", reportId).param("id", versionId)
                .query((rs, n) -> verification(rs)).optional();
    }

    @Override public boolean isTaskResult(UUID reportId, UUID versionId) {
        return jdbc.sql("select exists(select 1 from report_engineering_task_result where report_id=? and verification_version_id=?)")
                .params(reportId, versionId).query(Boolean.class).single();
    }

    @Override public boolean hasCorrection(UUID reportId, UUID versionId) {
        return jdbc.sql("select exists(select 1 from report_verification where report_id=? and previous_version_id=?)")
                .params(reportId, versionId).query(Boolean.class).single();
    }

    @Override public void addVerification(Context c, ActorContext actor, UUID id, UUID requestId,
                                           UUID previousVersionId, int version, VerificationInput f, String buildSummary) {
        jdbc.sql("""
                insert into report_verification(id, report_id, source_run_id, system_id, system_revision, workspace_id,
                    author_id, request_id, previous_version_id, version, question, suggestion_ref, origin, status,
                    method, environment, observation, impact, limits, blocker, upload_ids,
                    baseline_build_id, candidate_build_id, executed_by, build_summary)
                values (:id,:report,:run,:system,:revision,:workspace,:user,:request,:previous,:version,:question,
                    :reference,:origin,:status,:method,:environment,:observation,:impact,:limits,:blocker,:uploads,
                    :baseline,:candidate,:executor,:summary)
                """).param("id", id).param("report", c.reportId()).param("run", c.runId()).param("system", c.systemId())
                .param("revision", c.systemRevision()).param("workspace", actor.workspaceId()).param("user", actor.userId())
                .param("request", requestId).param("previous", previousVersionId).param("version", version)
                .param("question", f.question()).param("reference", f.suggestionRef()).param("origin", f.origin().name())
                .param("status", f.status().name()).param("method", f.method()).param("environment", f.environment())
                .param("observation", f.observation()).param("impact", f.impact().name()).param("limits", f.limits())
                .param("blocker", f.blocker()).param("uploads", f.uploadIds().toArray(UUID[]::new))
                .param("baseline", f.baselineBuildId()).param("candidate", f.candidateBuildId())
                .param("executor", f.executedBy()).param("summary", buildSummary).update();
    }

    @Override public void addDecision(Context c, ActorContext actor, UUID id, UUID requestId, DecisionInput f) {
        jdbc.sql("""
                insert into report_human_decision(id,report_id,author_id,request_id,choice,reason,reconsider_when)
                values (?,?,?,?,?,?,?)
                """).params(id,c.reportId(),actor.userId(),requestId,f.choice(),f.reason(),f.reconsiderWhen()).update();
        int count = jdbc.sql("""
                insert into report_decision_basis(report_id,decision_id,verification_version_id)
                select report_id,:decision,id from report_verification where report_id=:report and id in (:versions)
                """).param("report", c.reportId()).param("decision", id).param("versions", f.verificationVersionIds()).update();
        if (count != f.verificationVersionIds().size()) {
            throw new IllegalArgumentException("请选择本报告已保存的验证记录版本。");
        }
    }

    private static Verification verification(ResultSet rs) throws SQLException {
        var fields = new VerificationInput(rs.getString("question"), rs.getString("suggestion_ref"),
                Origin.valueOf(rs.getString("origin")), Execution.valueOf(rs.getString("status")),
                rs.getString("method"), rs.getString("environment"), rs.getString("observation"),
                Impact.valueOf(rs.getString("impact")), rs.getString("limits"), rs.getString("blocker"), ids(rs, "upload_ids"),
                id(rs, "baseline_build_id"), id(rs, "candidate_build_id"), rs.getString("executed_by"));
        return new Verification(id(rs,"id"), id(rs,"previous_version_id"), rs.getInt("version"), fields,
                author(rs), rs.getTimestamp("created_at").toInstant(), rs.getString("build_summary"));
    }

    private static Decision decision(ResultSet rs) throws SQLException {
        var fields = new DecisionInput(rs.getString("choice"), rs.getString("reason"), rs.getString("reconsider_when"), ids(rs, "basis"));
        return new Decision(id(rs,"id"), fields, author(rs), rs.getTimestamp("created_at").toInstant());
    }

    private static Author author(ResultSet rs) throws SQLException { return new Author(id(rs, "author_id"), rs.getString("display_name")); }
    private static UUID id(ResultSet rs, String column) throws SQLException { return rs.getObject(column, UUID.class); }
    private static List<UUID> ids(ResultSet rs, String column) throws SQLException { return List.of((UUID[])rs.getArray(column).getArray()); }
}
