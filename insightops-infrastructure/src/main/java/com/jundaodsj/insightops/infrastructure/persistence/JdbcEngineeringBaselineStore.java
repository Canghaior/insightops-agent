package com.jundaodsj.insightops.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.businesssystem.application.EngineeringBaselineStore;
import com.jundaodsj.insightops.identity.application.ActorContext;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@Repository
public class JdbcEngineeringBaselineStore implements EngineeringBaselineStore {
    private static final String SCOPE =
            "system_id=:system and workspace_id=:workspace and owner_user_id=:owner";
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcEngineeringBaselineStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private JdbcClient.StatementSpec scoped(String sql, ActorContext actor, UUID system) {
        return jdbc.sql(sql)
                .param("workspace", actor.workspaceId())
                .param("owner", actor.userId())
                .param("system", system);
    }

    @Override
    public Optional<Profile> profile(ActorContext actor, UUID system, boolean lock) {
        return scoped(
                        "select revision,technology_stack from business_system where id=:system and"
                                + " workspace_id=:workspace and owner_user_id=:owner"
                                + (lock ? " for update" : ""),
                        actor,
                        system)
                .query(
                        (rs, n) ->
                                new Profile(
                                        rs.getInt("revision"), rs.getString("technology_stack")))
                .optional();
    }

    private static Summary summary(ResultSet rs) throws SQLException {
        return new Summary(
                rs.getObject("id", UUID.class),
                rs.getObject("system_id", UUID.class),
                rs.getInt("version"),
                rs.getString("label"),
                rs.getTimestamp("saved_at").toInstant());
    }

    @Override
    public List<Summary> list(ActorContext actor, UUID system) {
        return scoped(
                        "select id,system_id,version,label,saved_at from engineering_baseline where"
                                + " "
                                + SCOPE
                                + " order by version desc",
                        actor,
                        system)
                .query((rs, n) -> summary(rs))
                .list();
    }

    @Override
    public Optional<Snapshot> find(ActorContext actor, UUID system, UUID id) {
        return scoped(
                        "select id,system_id,version,label,saved_at,parsed_content::text from"
                                + " engineering_baseline where "
                                + SCOPE
                                + " and id=:id",
                        actor,
                        system)
                .param("id", id)
                .query(
                        (rs, n) -> {
                            try {
                                return new Snapshot(
                                        summary(rs),
                                        json.readValue(
                                                rs.getString("parsed_content"), Content.class));
                            } catch (JsonProcessingException e) {
                                throw new IllegalStateException("无法读取已保存的工程基线。", e);
                            }
                        })
                .optional();
    }

    @Override
    public Optional<byte[]> original(ActorContext actor, UUID system, UUID id) {
        return scoped(
                        "select original_bytes from engineering_baseline where "
                                + SCOPE
                                + " and id=:id",
                        actor,
                        system)
                .param("id", id)
                .query((rs, n) -> rs.getBytes("original_bytes"))
                .optional();
    }

    @Override
    public Optional<Receipt> request(ActorContext actor, UUID system, UUID requestId) {
        return scoped(
                        "select id,request_hash from engineering_baseline where "
                                + SCOPE
                                + " and request_id=:request",
                        actor,
                        system)
                .param("request", requestId)
                .query(
                        (rs, n) ->
                                new Receipt(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("request_hash")))
                .optional();
    }

    @Override
    public Snapshot append(
            ActorContext actor,
            UUID system,
            UUID requestId,
            String requestHash,
            Content content,
            byte[] material) {
        UUID id = UUID.randomUUID();
        String parsed;
        try {
            parsed = json.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法保存工程基线。", e);
        }
        // The service holds this system's row lock across receipt lookup and append.
        var row =
                scoped(
                                """
insert into engineering_baseline
    (id,workspace_id,owner_user_id,system_id,version,label,request_id,request_hash,parsed_content,original_bytes)
select :id,:workspace,:owner,:system,coalesce(max(version),0)+1,:label,:request,:hash,cast(:parsed as jsonb),:bytes
from engineering_baseline where system_id=:system
returning id,system_id,version,label,saved_at
""",
                                actor,
                                system)
                        .param("id", id)
                        .param("label", content.label())
                        .param("request", requestId)
                        .param("hash", requestHash)
                        .param("parsed", parsed)
                        .param("bytes", material)
                        .query((rs, n) -> summary(rs))
                        .single();
        return new Snapshot(row, content);
    }
}
