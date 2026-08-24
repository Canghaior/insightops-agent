package com.jundaodsj.insightops.infrastructure.persistence;

import com.jundaodsj.insightops.agent.application.AgentWorkflowProductStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcAgentWorkflowProductStore implements AgentWorkflowProductStore {

    private final JdbcClient jdbc;

    public JdbcAgentWorkflowProductStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ParameterPreset> presets(
            UUID workspaceId, UUID ownerUserId, UUID templateId, UUID versionId) {
        return jdbc.sql("""
                        select * from agent_workflow_parameter_preset
                        where workspace_id = :workspaceId and owner_user_id = :ownerUserId
                          and template_id = :templateId and template_version_id = :versionId
                        order by updated_at desc, name
                        """)
                .param("workspaceId", workspaceId).param("ownerUserId", ownerUserId)
                .param("templateId", templateId).param("versionId", versionId)
                .query((rs, row) -> preset(rs)).list();
    }

    @Override
    @Transactional
    public ParameterPreset savePreset(PresetDraft draft) {
        jdbc.sql("""
                        insert into agent_workflow_parameter_preset (
                            id, workspace_id, owner_user_id, template_id,
                            template_version_id, name, values_json, created_at, updated_at
                        ) values (
                            :id, :workspaceId, :ownerUserId, :templateId,
                            :versionId, :name, cast(:values as jsonb), :now, :now
                        ) on conflict (owner_user_id, template_version_id, name)
                        do update set values_json = excluded.values_json, updated_at = excluded.updated_at
                        """)
                .param("id", draft.id()).param("workspaceId", draft.workspaceId())
                .param("ownerUserId", draft.ownerUserId()).param("templateId", draft.templateId())
                .param("versionId", draft.templateVersionId()).param("name", draft.name())
                .param("values", draft.valuesJson()).param("now", ts(draft.now())).update();
        return jdbc.sql("""
                        select * from agent_workflow_parameter_preset
                        where owner_user_id = :ownerUserId and template_version_id = :versionId
                          and name = :name
                        """)
                .param("ownerUserId", draft.ownerUserId()).param("versionId", draft.templateVersionId())
                .param("name", draft.name()).query((rs, row) -> preset(rs)).single();
    }

    @Override
    public boolean deletePreset(UUID workspaceId, UUID ownerUserId, UUID presetId) {
        return jdbc.sql("""
                        delete from agent_workflow_parameter_preset
                        where id = :id and workspace_id = :workspaceId and owner_user_id = :ownerUserId
                        """)
                .param("id", presetId).param("workspaceId", workspaceId)
                .param("ownerUserId", ownerUserId).update() == 1;
    }

    @Override
    public TemplateShare createShare(ShareDraft draft) {
        jdbc.sql("""
                        insert into agent_workflow_template_share (
                            id, source_workspace_id, template_id, template_version_id,
                            token_hash, status, expires_at, created_by, created_at
                        ) values (
                            :id, :workspaceId, :templateId, :versionId,
                            :tokenHash, 'ACTIVE', :expiresAt, :createdBy, :createdAt
                        )
                        """)
                .param("id", draft.id()).param("workspaceId", draft.sourceWorkspaceId())
                .param("templateId", draft.templateId()).param("versionId", draft.templateVersionId())
                .param("tokenHash", draft.tokenHash()).param("expiresAt", ts(draft.expiresAt()))
                .param("createdBy", draft.createdBy()).param("createdAt", ts(draft.createdAt())).update();
        return findShare(draft.id()).orElseThrow();
    }

    @Override
    public List<TemplateShare> shares(UUID workspaceId, UUID templateId) {
        return jdbc.sql("""
                        select * from agent_workflow_template_share
                        where source_workspace_id = :workspaceId and template_id = :templateId
                        order by created_at desc
                        """)
                .param("workspaceId", workspaceId).param("templateId", templateId)
                .query((rs, row) -> share(rs)).list();
    }

    @Override
    public Optional<TemplateShare> findActiveShare(String tokenHash, Instant now) {
        return jdbc.sql("""
                        select * from agent_workflow_template_share
                        where token_hash = :tokenHash and status = 'ACTIVE' and expires_at > :now
                        """)
                .param("tokenHash", tokenHash).param("now", ts(now))
                .query((rs, row) -> share(rs)).optional();
    }

    @Override
    public boolean revokeShare(UUID workspaceId, UUID shareId, Instant now) {
        return jdbc.sql("""
                        update agent_workflow_template_share
                        set status = 'REVOKED', revoked_at = :now
                        where id = :id and source_workspace_id = :workspaceId and status = 'ACTIVE'
                        """)
                .param("now", ts(now)).param("id", shareId).param("workspaceId", workspaceId)
                .update() == 1;
    }

    @Override
    public void recordImport(UUID shareId, Instant now) {
        jdbc.sql("""
                        update agent_workflow_template_share
                        set import_count = import_count + 1, last_imported_at = :now
                        where id = :id and status = 'ACTIVE' and expires_at > :now
                        """)
                .param("now", ts(now)).param("id", shareId).update();
    }

    @Override
    public List<WorkflowRunMetric> runMetrics(
            UUID workspaceId, UUID templateId, Instant from, int limit) {
        return jdbc.sql("""
                        with answer_feedback as (
                            select run_id,
                                   count(*) filter (where helpful is not null)::int as feedback_count,
                                   count(*) filter (where helpful is true)::int as helpful_count
                            from research_answer_feedback group by run_id
                        ), citation_feedback as (
                            select run_id, count(*)::int as citation_count,
                                   count(*) filter (where correct)::int as correct_count
                            from research_citation_feedback group by run_id
                        ), node_metrics as (
                            select run_id, count(*)::int as node_count,
                                   count(*) filter (where status in ('SUCCEEDED', 'REUSED'))::int
                                       as successful_node_count
                            from agent_workflow_run_node group by run_id
                        )
                        select awr.run_id, awr.template_version_snapshot, ar.status,
                               awr.created_at, ar.started_at, ar.finished_at,
                               coalesce(ar.prompt_tokens, 0) + coalesce(ar.completion_tokens, 0)
                                   as total_tokens,
                               coalesce(ar.estimated_cost_cny, 0) as estimated_cost_cny,
                               case when coalesce(af.feedback_count, 0) = 0 then null
                                    else af.helpful_count * 2 >= af.feedback_count end as helpful,
                               coalesce(af.feedback_count, 0) as feedback_count,
                               coalesce(af.helpful_count, 0) as helpful_count,
                               coalesce(cf.citation_count, 0) as citation_count,
                               coalesce(cf.correct_count, 0) as correct_count,
                               coalesce(nm.node_count, 0) as node_count,
                               coalesce(nm.successful_node_count, 0) as successful_node_count
                        from agent_workflow_run awr
                        join agent_run ar on ar.id = awr.run_id
                        left join answer_feedback af on af.run_id = awr.run_id
                        left join citation_feedback cf on cf.run_id = awr.run_id
                        left join node_metrics nm on nm.run_id = awr.run_id
                        where awr.workspace_id = :workspaceId and awr.template_id = :templateId
                          and awr.created_at >= :from
                        order by awr.created_at desc
                        limit :limit
                        """)
                .param("workspaceId", workspaceId).param("templateId", templateId)
                .param("from", ts(from)).param("limit", Math.max(1, Math.min(limit, 5_000)))
                .query((rs, row) -> metric(rs)).list();
    }

    private Optional<TemplateShare> findShare(UUID id) {
        return jdbc.sql("select * from agent_workflow_template_share where id = :id")
                .param("id", id).query((rs, row) -> share(rs)).optional();
    }

    private static ParameterPreset preset(ResultSet rs) throws SQLException {
        return new ParameterPreset(
                rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class),
                rs.getObject("owner_user_id", UUID.class), rs.getObject("template_id", UUID.class),
                rs.getObject("template_version_id", UUID.class), rs.getString("name"),
                rs.getString("values_json"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static TemplateShare share(ResultSet rs) throws SQLException {
        return new TemplateShare(
                rs.getObject("id", UUID.class), rs.getObject("source_workspace_id", UUID.class),
                rs.getObject("template_id", UUID.class), rs.getObject("template_version_id", UUID.class),
                rs.getString("status"), instant(rs, "expires_at"),
                rs.getObject("created_by", UUID.class), instant(rs, "created_at"),
                instant(rs, "revoked_at"), rs.getInt("import_count"), instant(rs, "last_imported_at"));
    }

    private static WorkflowRunMetric metric(ResultSet rs) throws SQLException {
        return new WorkflowRunMetric(
                rs.getObject("run_id", UUID.class), rs.getInt("template_version_snapshot"),
                rs.getString("status"), instant(rs, "created_at"), instant(rs, "started_at"),
                instant(rs, "finished_at"), rs.getLong("total_tokens"),
                rs.getBigDecimal("estimated_cost_cny"), rs.getObject("helpful", Boolean.class),
                rs.getInt("feedback_count"), rs.getInt("helpful_count"),
                rs.getInt("citation_count"), rs.getInt("correct_count"), rs.getInt("node_count"),
                rs.getInt("successful_node_count"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp ts(Instant value) {
        return Timestamp.from(value);
    }
}
