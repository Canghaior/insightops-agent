package com.jundaodsj.insightops.infrastructure.persistence;

import com.jundaodsj.insightops.agent.application.AgentWorkflowTemplateStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcAgentWorkflowTemplateStore implements AgentWorkflowTemplateStore {

    private final JdbcClient jdbc;

    public JdbcAgentWorkflowTemplateStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<WorkflowTemplate> overview(UUID workspaceId) {
        return jdbc.sql("""
                        select id from agent_workflow_template
                        where workspace_id = :workspaceId
                        order by updated_at desc, name
                        """)
                .param("workspaceId", workspaceId)
                .query(UUID.class).list().stream()
                .map(id -> find(workspaceId, id).orElseThrow()).toList();
    }

    @Override
    public Optional<WorkflowTemplate> find(UUID workspaceId, UUID templateId) {
        List<WorkflowVersion> versions = jdbc.sql("""
                        select * from agent_workflow_template_version
                        where template_id = :templateId
                        order by version desc
                        """)
                .param("templateId", templateId)
                .query((rs, row) -> version(rs)).list();
        return jdbc.sql("""
                        select * from agent_workflow_template
                        where id = :id and workspace_id = :workspaceId
                        """)
                .param("id", templateId).param("workspaceId", workspaceId)
                .query((rs, row) -> template(rs, versions)).optional();
    }

    @Override
    @Transactional
    public WorkflowTemplate create(
            UUID workspaceId, UUID userId, TemplateDraft draft, Instant now) {
        lock("agent-workflow:name:" + workspaceId + ":" + draft.name());
        UUID templateId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Map<String, Object> params = new HashMap<>();
        params.put("templateId", templateId);
        params.put("workspaceId", workspaceId);
        params.put("name", draft.name());
        params.put("description", draft.description());
        params.put("category", draft.category());
        params.put("userId", userId);
        params.put("now", Timestamp.from(now));
        jdbc.sql("""
                        insert into agent_workflow_template
                            (id, workspace_id, name, description, category, status,
                             created_by, created_at, updated_at)
                        values (:templateId, :workspaceId, :name, :description, :category,
                                'ACTIVE', :userId, :now, :now)
                        """).params(params).update();
        insertVersion(templateId, versionId, 1, userId, draft.version(), now);
        return find(workspaceId, templateId).orElseThrow();
    }

    @Override
    @Transactional
    public WorkflowTemplate createVersion(
            UUID workspaceId, UUID templateId, UUID userId,
            VersionDraft draft, Instant now) {
        lock("agent-workflow:version:" + templateId);
        requireTemplate(workspaceId, templateId);
        int version = jdbc.sql("""
                        select coalesce(max(version), 0) + 1
                        from agent_workflow_template_version where template_id = :templateId
                        """).param("templateId", templateId).query(Integer.class).single();
        insertVersion(templateId, UUID.randomUUID(), version, userId, draft, now);
        jdbc.sql("""
                        update agent_workflow_template set updated_at = :now
                        where id = :id and workspace_id = :workspaceId
                        """).param("now", Timestamp.from(now)).param("id", templateId)
                .param("workspaceId", workspaceId).update();
        return find(workspaceId, templateId).orElseThrow();
    }

    @Override
    @Transactional
    public WorkflowTemplate activate(
            UUID workspaceId, UUID templateId, UUID versionId, UUID userId,
            String reason, Instant now) {
        lock("agent-workflow:activate:" + templateId);
        WorkflowTemplate template = requireTemplate(workspaceId, templateId);
        WorkflowVersion selected = template.versions().stream()
                .filter(item -> item.id().equals(versionId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Workflow version not found"));
        UUID previous = template.activeVersionId();
        if (previous != null && !previous.equals(versionId)) {
            jdbc.sql("""
                            update agent_workflow_template_version
                            set status = 'RETIRED'
                            where id = :id and status = 'ACTIVE'
                            """).param("id", previous).update();
        }
        jdbc.sql("""
                        update agent_workflow_template_version
                        set status = 'ACTIVE', activated_at = :now where id = :id
                        """).param("id", selected.id()).param("now", Timestamp.from(now)).update();
        jdbc.sql("""
                        update agent_workflow_template
                        set active_version_id = :versionId, status = 'ACTIVE', updated_at = :now
                        where id = :templateId and workspace_id = :workspaceId
                        """).param("versionId", versionId).param("now", Timestamp.from(now))
                .param("templateId", templateId).param("workspaceId", workspaceId).update();
        Map<String, Object> params = new HashMap<>();
        params.put("id", UUID.randomUUID());
        params.put("workspaceId", workspaceId);
        params.put("templateId", templateId);
        params.put("previous", previous);
        params.put("versionId", versionId);
        params.put("userId", userId);
        params.put("reason", reason);
        params.put("now", Timestamp.from(now));
        jdbc.sql("""
                        insert into agent_workflow_activation_audit
                            (id, workspace_id, template_id, previous_version_id,
                             activated_version_id, activated_by, reason, created_at)
                        values (:id, :workspaceId, :templateId, :previous,
                                :versionId, :userId, :reason, :now)
                        """).params(params).update();
        return find(workspaceId, templateId).orElseThrow();
    }

    private void insertVersion(
            UUID templateId, UUID versionId, int version, UUID userId,
            VersionDraft draft, Instant now) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", versionId);
        params.put("templateId", templateId);
        params.put("version", version);
        params.put("summary", draft.summary());
        params.put("entryQuestion", draft.entryQuestion());
        params.put("graph", draft.graphSpecJson());
        params.put("userId", userId);
        params.put("now", Timestamp.from(now));
        jdbc.sql("""
                        insert into agent_workflow_template_version
                            (id, template_id, version, status, summary, entry_question,
                             graph_spec, created_by, created_at)
                        values (:id, :templateId, :version, 'DRAFT', :summary, :entryQuestion,
                                cast(:graph as jsonb), :userId, :now)
                        """).params(params).update();
    }

    private WorkflowTemplate requireTemplate(UUID workspaceId, UUID templateId) {
        return find(workspaceId, templateId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow template not found"));
    }

    private void lock(String key) {
        jdbc.sql("select 1 from (select pg_advisory_xact_lock(hashtext(:key))) locked")
                .param("key", key).query(Integer.class).single();
    }

    private WorkflowTemplate template(ResultSet rs, List<WorkflowVersion> versions)
            throws SQLException {
        return new WorkflowTemplate(
                rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class),
                rs.getString("name"), rs.getString("description"), rs.getString("category"),
                rs.getString("status"), rs.getObject("active_version_id", UUID.class),
                rs.getObject("created_by", UUID.class), instant(rs, "created_at"),
                instant(rs, "updated_at"), versions);
    }

    private WorkflowVersion version(ResultSet rs) throws SQLException {
        return new WorkflowVersion(
                rs.getObject("id", UUID.class), rs.getObject("template_id", UUID.class),
                rs.getInt("version"), rs.getString("status"), rs.getString("summary"),
                rs.getString("entry_question"), rs.getString("graph_spec"),
                rs.getObject("created_by", UUID.class), instant(rs, "created_at"),
                instant(rs, "activated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
