package com.jundaodsj.insightops.infrastructure.persistence;

import com.jundaodsj.insightops.businesssystem.application.BusinessSystemStore;
import com.jundaodsj.insightops.identity.application.ActorContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcBusinessSystemStore implements BusinessSystemStore {
    private final JdbcClient jdbc;

    public JdbcBusinessSystemStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    private JdbcClient.StatementSpec scoped(String sql, ActorContext actor) {
        return jdbc.sql(sql).param("workspace", actor.workspaceId()).param("owner", actor.userId());
    }

    @Override
    public List<Summary> list(ActorContext actor) {
        return scoped("""
                select id, name, purpose, revision, updated_at from business_system
                where workspace_id=:workspace and owner_user_id=:owner order by updated_at desc, id
                """, actor).query((rs, row) -> new Summary(rs.getObject("id", UUID.class),
                rs.getString("name"), rs.getString("purpose"), rs.getInt("revision"),
                rs.getTimestamp("updated_at").toInstant())).list();
    }

    @Override
    public Optional<Profile> find(ActorContext actor, UUID id) {
        // One statement sees the same revision of both the text and its attachment links.
        return scoped("""
                select s.*, array(select a.upload_id from business_system_upload a
                    where a.system_id=s.id order by a.position) as uploads
                from business_system s where s.workspace_id=:workspace and s.owner_user_id=:owner and s.id=:id
                """, actor).param("id", id).query((rs, row) -> profile(rs)).optional();
    }

    private static Profile profile(ResultSet rs) throws SQLException {
        var background = new Background(rs.getString("name"), rs.getString("purpose"),
                rs.getString("key_processes"), rs.getString("technology_stack"),
                rs.getString("current_problems"), rs.getString("constraints_text"));
        return new Profile(rs.getObject("id", UUID.class), rs.getInt("revision"), background,
                List.of((UUID[]) rs.getArray("uploads").getArray()), rs.getTimestamp("updated_at").toInstant());
    }

    @Override
    @Transactional
    public UUID create(ActorContext actor, Background background, List<UUID> uploadIds) {
        UUID id = UUID.randomUUID();
        fields(scoped("""
                insert into business_system (id, workspace_id, owner_user_id, name, purpose,
                    key_processes, technology_stack, current_problems, constraints_text)
                values (:id, :workspace, :owner, :name, :purpose, :processes, :stack, :problems, :constraints)
                """, actor), background).param("id", id).update();
        insertUploads(id, uploadIds);
        return id;
    }

    @Override
    @Transactional
    public boolean update(ActorContext actor, UUID id, int expectedRevision, Background background, List<UUID> uploadIds) {
        int updated = fields(scoped("""
                update business_system set name=:name, purpose=:purpose, key_processes=:processes,
                    technology_stack=:stack, current_problems=:problems, constraints_text=:constraints,
                    revision=revision+1, updated_at=now()
                where id=:id and workspace_id=:workspace and owner_user_id=:owner and revision=:revision
                """, actor), background).param("id", id).param("revision", expectedRevision).update();
        if (updated == 0) return false;
        jdbc.sql("delete from business_system_upload where system_id=:id").param("id", id).update();
        insertUploads(id, uploadIds);
        return true;
    }

    private static JdbcClient.StatementSpec fields(JdbcClient.StatementSpec statement, Background background) {
        return statement.param("name", background.name()).param("purpose", background.purpose())
                .param("processes", background.keyProcesses()).param("stack", background.technologyStack())
                .param("problems", background.currentProblems()).param("constraints", background.constraints());
    }

    private void insertUploads(UUID id, List<UUID> uploadIds) {
        for (int i = 0; i < uploadIds.size(); i++) {
            jdbc.sql("insert into business_system_upload(system_id, upload_id, position) values (:id, :upload, :position)")
                    .param("id", id).param("upload", uploadIds.get(i)).param("position", i).update();
        }
    }

    @Override
    public List<Run> runs(ActorContext actor, UUID id, int limit, long offset) {
        return scoped("""
                select w.run_id, w.input_snapshot->>'projectName' as project_name,
                    w.input_snapshot->>'currentVersion' as current_version, w.input_snapshot->>'targetVersion' as target_version,
                    w.input_snapshot #>> '{systemSnapshot,revision}' as system_revision, r.status, w.created_at
                from agent_workflow_run w join agent_run r on r.id=w.run_id
                where w.workspace_id=:workspace and w.owner_user_id=:owner and w.business_system_id=:id
                order by w.created_at desc, w.run_id limit :limit offset :offset
                """, actor).param("id", id).param("limit", limit).param("offset", offset)
                .query((rs, row) -> new Run(rs.getObject("run_id", UUID.class), rs.getString("project_name"),
                        rs.getString("current_version"), rs.getString("target_version"), rs.getInt("system_revision"),
                        rs.getString("status"), rs.getTimestamp("created_at").toInstant())).list();
    }
}
