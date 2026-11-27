package com.jundaodsj.insightops.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.report.application.BuildEvidence.*;
import com.jundaodsj.insightops.report.application.BuildEvidence.Module;
import com.jundaodsj.insightops.report.application.BuildEvidenceStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcBuildEvidenceStore implements BuildEvidenceStore {
    private record Analysis(BuildRecord commandRecord, LogFacts log, List<ExcludedMethod> excludedMethods,
                            List<TestCase> cases, List<String> warnings, List<Module> modules, BuildTool buildTool) {}
    private record Details(Summary summary, Metadata metadata, Analysis analysis) {}

    private static final String SCOPE = " b.workspace_id=:workspace and b.owner_user_id=:owner and b.system_id=:system";
    private static final String SUMMARY = "b.id, b.system_id, b.report_id, b.role, b.metadata->>'label' as label, b.imported_at, b.counts";
    private static final String MATERIAL = "f.id, f.file_name, f.kind, f.byte_size, f.sha256, f.module_path";
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcBuildEvidenceStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private JdbcClient.StatementSpec scoped(String sql, ActorContext actor, UUID system) {
        return jdbc.sql(sql).param("workspace", actor.workspaceId()).param("owner", actor.userId()).param("system", system);
    }

    @Override public List<Summary> list(ActorContext actor, UUID system) {
        return scoped("select " + SUMMARY + " from build_evidence_batch b where " + SCOPE
                        + " order by b.imported_at desc, b.id", actor, system)
                .query((rs, n) -> summary(rs)).list();
    }

    @Override public Optional<Batch> find(ActorContext actor, UUID system, UUID batchId) {
        var details = scoped("select " + SUMMARY + ", b.metadata, b.analysis from build_evidence_batch b where "
                        + SCOPE + " and b.id=:id", actor, system).param("id", batchId)
                .query((rs, n) -> new Details(summary(rs), read(rs, "metadata", Metadata.class),
                        read(rs, "analysis", Analysis.class))).optional();
        return details.map(parts -> {
            var files = jdbc.sql("select " + MATERIAL + " from build_evidence_file f where batch_id=:id order by f.position")
                    .param("id", batchId).query((rs, n) -> file(rs)).list();
            Analysis a = parts.analysis();
            return new Batch(parts.summary(), parts.metadata(), a.commandRecord(), a.log(), a.excludedMethods(),
                    files, a.cases(), a.warnings(), a.modules(), a.buildTool());
        });
    }

    @Override public Optional<Original> original(ActorContext actor, UUID system, UUID batchId, UUID fileId) {
        return scoped("select " + MATERIAL + ", f.content from build_evidence_file f join build_evidence_batch b on b.id=f.batch_id where "
                        + SCOPE + " and b.id=:batch and f.id=:file", actor, system)
                .param("batch", batchId).param("file", fileId)
                .query((rs, n) -> new Original(file(rs), rs.getBytes("content"))).optional();
    }

    @Override public Optional<Receipt> request(ActorContext actor, UUID system, UUID requestId) {
        return scoped("select b.id, b.request_hash from build_evidence_batch b where " + SCOPE
                        + " and b.request_id=:request", actor, system).param("request", requestId)
                .query((rs, n) -> new Receipt(rs.getObject("id", UUID.class), rs.getString("request_hash"))).optional();
    }

    @Override @Transactional
    public boolean insert(ActorContext actor, UUID requestId, String fingerprint, Batch batch, List<Original> originals) {
        Summary s = batch.summary();
        var analysis = new Analysis(batch.commandRecord(), batch.log(), batch.excludedMethods(), batch.cases(),
                batch.warnings(), batch.modules(), batch.buildTool());
        int inserted = jdbc.sql("""
                insert into build_evidence_batch
                    (id, workspace_id, owner_user_id, system_id, report_id, request_id, request_hash,
                     role, imported_at, metadata, counts, analysis)
                values (:id,:workspace,:owner,:system,:report,:request,:hash,:role,:at,
                        cast(:metadata as jsonb),cast(:counts as jsonb),cast(:analysis as jsonb))
                on conflict (workspace_id, owner_user_id, system_id, request_id) do nothing
                """).param("id", s.id()).param("workspace", actor.workspaceId()).param("owner", actor.userId())
                .param("system", s.systemId()).param("report", s.reportId()).param("request", requestId)
                .param("hash", fingerprint).param("role", s.role().name()).param("at", Timestamp.from(s.importedAt()))
                .param("metadata", write(batch.metadata())).param("counts", write(s.counts()))
                .param("analysis", write(analysis)).update();
        if (inserted == 0) return false;
        int position = 0;
        for (Original original : originals) {
            EvidenceFile f = original.file();
            jdbc.sql("""
                    insert into build_evidence_file(id,batch_id,position,file_name,kind,sha256,byte_size,content,module_path)
                    values (?,?,?,?,?,?,?,?,?)
                    """).params(f.id(), s.id(), position++, f.name(), f.kind().name(), f.sha256(), f.size(), original.content(), f.modulePath())
                    .update();
        }
        return true;
    }

    private Summary summary(ResultSet rs) throws SQLException {
        return new Summary(rs.getObject("id", UUID.class), rs.getObject("system_id", UUID.class), rs.getObject("report_id", UUID.class),
                Role.valueOf(rs.getString("role")), rs.getString("label"), rs.getTimestamp("imported_at").toInstant(),
                read(rs, "counts", Counts.class));
    }

    private static EvidenceFile file(ResultSet rs) throws SQLException {
        return new EvidenceFile(rs.getObject("id", UUID.class), rs.getString("file_name"), FileKind.valueOf(rs.getString("kind")),
                rs.getLong("byte_size"), rs.getString("sha256"), rs.getString("module_path"));
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("无法归档构建证据。", e); }
    }

    private <T> T read(ResultSet rs, String field, Class<T> type) throws SQLException {
        try { return json.readValue(rs.getString(field), type); }
        catch (JsonProcessingException e) { throw new IllegalStateException("已保存的构建证据格式错误。", e); }
    }
}
