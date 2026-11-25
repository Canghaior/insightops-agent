package com.jundaodsj.insightops.server.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.report.application.ReportDeliveryStore;
import com.jundaodsj.insightops.report.application.ReportDeliveryStore.ResearchSnapshot;
import com.jundaodsj.insightops.report.application.ReportReviewStore;
import com.jundaodsj.insightops.server.businesssystem.EngineeringBaselineService;
import com.jundaodsj.insightops.server.workflow.ReleaseVersionRange;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import static com.jundaodsj.insightops.server.workflow.ResearchProfiles.text;

@Service
public class ReportClaimReviews {
    public enum Verdict { SUPPORTS, INSUFFICIENT, CONTRADICTS }
    public record Draft(int answerLine, String path, Integer codeLine, String source, String quotation,
                        Verdict verdict, String reason, String test, UUID verificationId) {}
    public record Command(UUID requestId, Draft draft) {}
    public record Evidence(String claim, String code, String targetVersion, String sourceUrl, String sourceVersion,
                           String verificationStatus, String observation) {}
    public record Saved(UUID id, UUID authorId, Instant createdAt, Draft draft, Evidence evidence) {}

    private final ReportDeliveryStore reports;
    private final ReportReviewStore verifications;
    private final EngineeringBaselineService baselines;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ReportClaimReviews(ReportDeliveryStore reports, ReportReviewStore verifications,
                              EngineeringBaselineService baselines, JdbcClient jdbc, ObjectMapper json) {
        this.reports = reports; this.verifications = verifications; this.baselines = baselines;
        this.jdbc = jdbc; this.json = json;
    }

    private ResearchSnapshot report(ActorContext actor, UUID id) {
        var report = reports.findReport(actor, id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到报告。"));
        var snapshot = report.research();
        if (snapshot == null || snapshot.evidence() == null || snapshot.evidence().stream().noneMatch(row ->
                Objects.equals(row.get("claimReviewVersion"), 1))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该快照未启用逐条依据核对。");
        }
        return snapshot;
    }

    @Transactional(readOnly = true)
    public List<Saved> list(ActorContext actor, UUID id) {
        report(actor, id);
        return jdbc.sql("SELECT * FROM report_claim_review WHERE report_id=:id ORDER BY created_at DESC,id")
                .param("id", id).query(this::read).list();
    }

    @Transactional
    public Saved add(ActorContext actor, UUID id, Command command) {
        var report = report(actor, id);
        if (command == null || command.requestId() == null || command.draft() == null)
            throw new IllegalArgumentException("缺少核对记录或请求标识。");
        var d = command.draft();
        var draft = new Draft(d.answerLine(), text(d.path(), 400, false), d.codeLine(), text(d.source(), 40, false),
                text(d.quotation(), 1200, false), Objects.requireNonNullElse(d.verdict(), Verdict.INSUFFICIENT),
                text(d.reason(), 1500, true), text(d.test(), 1000, true), d.verificationId());
        var prior = jdbc.sql("SELECT * FROM report_claim_review WHERE report_id=:id AND author_id=:author AND request_id=:key")
                .param("id", id).param("author", actor.userId()).param("key", command.requestId());
        var previous = prior.query(this::read).optional();
        if (previous.isPresent()) return checkReplay(previous.get(), draft);
        var answer = line(report.answer(), draft.answerLine());
        String code = code(actor, report, draft);
        String target = Objects.toString(report.inputs().get("targetVersion"), "");
        var source = report.citations().stream().filter(c -> c.label().equals(draft.source())).findFirst();
        if (!draft.source().isBlank() && source.isEmpty()) throw new IllegalArgumentException("来源编号不属于冻结报告。");
        if (!draft.quotation().isBlank()) {
            if (!containsPassage(report, draft.source(), draft.quotation()))
                throw new IllegalArgumentException("所引文字必须逐字来自该编号的冻结资料片段。");
        }
        if (draft.verdict() == Verdict.SUPPORTS || draft.verdict() == Verdict.CONTRADICTS) {
            if (!draft.source().isBlank() && draft.quotation().isBlank())
                throw new IllegalArgumentException("请从选定资料摘录支持或反驳建议的原文。");
            if (code.isEmpty() && draft.quotation().isEmpty())
                throw new IllegalArgumentException("作出依据判断需要一处冻结的工程位置或外部原文。");
            if (!target.isEmpty() && !matchesTarget(source.map(c -> c.versionLabel()).orElse(""), target))
                throw new IllegalArgumentException("升级判断需要目标版本的冻结原文。可记录「依据不足」及缺项。");
        }
        var verification = draft.verificationId() == null ? null :
                verifications.verification(id, draft.verificationId()).orElseThrow(() ->
                        new IllegalArgumentException("关联验证版本不属于这份报告。"));
        var evidence = new Evidence(answer, code, target, source.map(c -> c.url()).orElse(""),
                source.map(c -> c.versionLabel()).orElse(""), verification == null ? "" : verification.fields().status().name(),
                verification == null ? "" : verification.fields().observation());
        jdbc.sql("""
                INSERT INTO report_claim_review (id,report_id,author_id,request_id,verification_id,draft,evidence)
                VALUES (:newId,:id,:author,:key,:v,CAST(:d AS jsonb),CAST(:e AS jsonb))
                ON CONFLICT (report_id,author_id,request_id) DO NOTHING
                """).param("newId", UUID.randomUUID()).param("id", id).param("author", actor.userId())
                .param("key", command.requestId()).param("v", draft.verificationId()).param("d", write(draft))
                .param("e", write(evidence)).update();
        return checkReplay(prior.query(this::read).single(), draft);
    }

    private boolean containsPassage(ResearchSnapshot report, String label, String quote) {
        for (var row : report.evidence()) {
            if (!(row.get("passages") instanceof List<?> list)) continue;
            for (var item : list) {
                var p = json.valueToTree(item);
                if (label.equals(p.path("label").asText()) && p.path("text").asText().contains(quote)) return true;
            }
        }
        return false;
    }

    static boolean matchesTarget(String version, String target) {
        if (version == null || !version.matches("(?i)v?\\d+\\.\\d+\\.\\d+.*")) return false;
        try { return ReleaseVersionRange.parse(version).equals(ReleaseVersionRange.parse(target)); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private String code(ActorContext actor, ResearchSnapshot report, Draft d) {
        if (d.path().isEmpty() && d.codeLine() == null) return "";
        if (d.path().isEmpty() || d.codeLine() == null) throw new IllegalArgumentException("请选择工程原件并填写实际行号。");
        var meta = json.valueToTree(report.inputs()).path("engineeringBaseline").path("baseline");
        if (!meta.hasNonNull("id") || !meta.hasNonNull("systemId")) throw new IllegalArgumentException("报告未冻结工程基线。");
        var file = baselines.download(actor, UUID.fromString(meta.path("systemId").asText()),
                UUID.fromString(meta.path("id").asText()), d.path());
        return line(new String(file.bytes(), StandardCharsets.UTF_8), d.codeLine());
    }

    private static String line(String text, int number) {
        if (number < 1 || text == null) throw new IllegalArgumentException("行号不存在。");
        String line = text.lines().skip(number - 1L).findFirst().orElse("").strip();
        if (line.isEmpty() || line.length() > 4000) throw new IllegalArgumentException("请选择有内容且不超过 4000 字符的一行。");
        return line;
    }

    private Saved read(ResultSet rs, int row) throws SQLException {
        try { return new Saved(rs.getObject("id", UUID.class), rs.getObject("author_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(), json.readValue(rs.getString("draft"), Draft.class),
                json.readValue(rs.getString("evidence"), Evidence.class)); }
        catch (JsonProcessingException e) { throw new SQLException("无法读取依据核对记录", e); }
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("无法保存依据核对记录", e); }
    }
    private static Saved checkReplay(Saved saved, Draft draft) {
        if (!saved.draft().equals(draft)) throw new ResponseStatusException(HttpStatus.CONFLICT, "提交标识已用于不同核对内容。");
        return saved;
    }
}
