package com.jundaodsj.insightops.server.report;

import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.report.application.ReportReviewStore;
import com.jundaodsj.insightops.report.application.ReportReviewStore.*;
import com.jundaodsj.insightops.server.workflow.ResearchMaterials;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import static com.jundaodsj.insightops.server.workflow.ResearchProfiles.text;

@Service
public class ReportReviewService {
    public record VerificationCommand(UUID requestId, UUID previousVersionId, VerificationInput fields) {}
    public record DecisionCommand(UUID requestId, DecisionInput fields) {}

    private final ReportReviewStore store;
    private final ResearchMaterials materials;
    public ReportReviewService(ReportReviewStore store, ResearchMaterials materials) {
        this.store = store;
        this.materials = materials;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public History history(ActorContext actor, UUID reportId) {
        var context = context(actor, reportId, false);
        return new History(context, store.verifications(reportId), store.decisions(reportId));
    }

    @Transactional
    public Verification record(ActorContext actor, UUID reportId, VerificationCommand cmd) {
        var context = context(actor, reportId, true);
        if (cmd == null || cmd.requestId() == null || cmd.fields() == null) throw invalidRequest();
        var fields = validate(cmd.fields());
        var prior = store.verificationRequest(reportId, cmd.requestId());
        if (prior.isPresent()) {
            var saved = prior.get();
            if (!saved.fields().equals(fields) || !Objects.equals(saved.previousVersionId(), cmd.previousVersionId())) throw reused();
            return saved;
        }
        int version = 1;
        if (cmd.previousVersionId() != null) {
            var previous = store.verification(reportId, cmd.previousVersionId()).orElseThrow(() ->
                    new IllegalArgumentException("要更正的记录不属于本报告。"));
            if (store.hasCorrection(reportId, previous.id())) throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "该版本已有后续更正，请刷新后从最新版本补充。");
            version = previous.version() + 1;
        }
        materials.require(actor, fields.uploadIds());
        var id = UUID.randomUUID();
        store.addVerification(context, actor, id, cmd.requestId(), cmd.previousVersionId(), version, fields);
        return store.verification(reportId, id).orElseThrow();
    }

    @Transactional
    public Decision decide(ActorContext actor, UUID reportId, DecisionCommand cmd) {
        var context = context(actor, reportId, true);
        if (cmd == null || cmd.requestId() == null || cmd.fields() == null) throw invalidRequest();
        var f = cmd.fields();
        var basis = ids(f.verificationVersionIds(), 50);
        if (basis.isEmpty()) throw new IllegalArgumentException("请至少选择一条已保存的验证记录作为依据。");
        var fields = new DecisionInput(text(f.choice(), 500, true), text(f.reason(), 2000, true),
                text(f.reconsiderWhen(), 2000, true), basis);
        var prior = store.decisionRequest(reportId, cmd.requestId());
        if (prior.isPresent()) {
            if (!prior.get().fields().equals(fields)) throw reused();
            return prior.get();
        }
        store.addDecision(context, actor, UUID.randomUUID(), cmd.requestId(), fields);
        return store.decisionRequest(reportId, cmd.requestId()).orElseThrow();
    }

    private Context context(ActorContext actor, UUID reportId, boolean lock) {
        return store.context(actor, reportId, lock).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到可记录验证的系统决策报告。"));
    }

    private static VerificationInput validate(VerificationInput f) {
        if (f.origin() == null || f.status() == null || f.impact() == null)
            throw new IllegalArgumentException("请选择建议来源、执行状态和对判断的影响。");
        boolean executed = f.status() == Execution.EXECUTED;
        String method = text(f.method(), 2000, f.status() != Execution.BLOCKED);
        String env = text(f.environment(), 1000, executed);
        String observation = text(f.observation(), 4000, executed);
        String blocker = text(f.blocker(), 1000, f.status() == Execution.BLOCKED);
        if (!executed && (!observation.isEmpty() || f.impact() != Impact.INCONCLUSIVE))
            throw new IllegalArgumentException("未执行或受阻时请保留无法判断，实际观察应在执行后填写。");
        if (f.status() != Execution.BLOCKED && !blocker.isEmpty())
            throw new IllegalArgumentException("仅在受阻时填写阻塞原因。");
        return new VerificationInput(text(f.question(), 1000, true), text(f.suggestionRef(), 2000, true),
                f.origin(), f.status(), method, env, observation, f.impact(), text(f.limits(), 2000, executed),
                blocker, ids(f.uploadIds(), 10));
    }

    private static List<UUID> ids(List<UUID> ids, int max) {
        if (ids == null) return List.of();
        if (ids.size() > max || ids.stream().anyMatch(Objects::isNull) || ids.stream().distinct().count() != ids.size())
            throw new IllegalArgumentException("所选记录或资料无效，请重新选择。");
        return ids.stream().sorted(Comparator.comparing(UUID::toString)).toList();
    }

    private static IllegalArgumentException invalidRequest() { return new IllegalArgumentException("缺少提交标识或记录内容。"); }
    private static ResponseStatusException reused() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "此提交标识已有不同内容，请核对已保存记录。");
    }
}
