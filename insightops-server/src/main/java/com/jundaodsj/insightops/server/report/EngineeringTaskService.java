package com.jundaodsj.insightops.server.report;

import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.report.application.EngineeringTaskStore;
import com.jundaodsj.insightops.report.application.EngineeringTaskStore.*;
import com.jundaodsj.insightops.report.application.ReportDeliveryStore.ReportRecord;
import com.jundaodsj.insightops.report.application.ReportReviewStore;
import com.jundaodsj.insightops.report.application.ReportReviewStore.Context;
import com.jundaodsj.insightops.report.application.ReportReviewStore.Execution;
import com.jundaodsj.insightops.report.application.ReportReviewStore.Impact;
import com.jundaodsj.insightops.report.application.ReportReviewStore.Origin;
import com.jundaodsj.insightops.report.application.ReportReviewStore.VerificationInput;
import com.jundaodsj.insightops.server.workflow.ResearchMaterials;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.jundaodsj.insightops.server.workflow.ResearchProfiles.text;

@Service
public class EngineeringTaskService {
    public static final int MAX_PATCH_BYTES = 1024 * 1024;

    public record TaskCommand(UUID requestId, UUID previousVersionId, TaskFields fields) {}
    public record ResultCommand(UUID requestId, UUID previousVerificationVersionId, Execution status, Impact impact,
                                String method, String environment, String observation, String limits, String blocker,
                                String executedBy, Instant executedAt, String sourceRevision, String candidateRevision,
                                String unfinished, List<UUID> uploadIds, UUID baselineBuildId, UUID candidateBuildId,
                                boolean retainPreviousPatch) {}
    public record PatchFile(String name, byte[] bytes) {}
    private record FrozenBaseline(UUID id, Integer version) {}
    private record PreparedPatch(PatchInfo info, byte[] bytes) {}

    private final EngineeringTaskStore tasks;
    private final ReportReviewStore reviews;
    private final ReportReviewService reviewService;
    private final ReportService reports;
    private final ResearchMaterials materials;

    public EngineeringTaskService(EngineeringTaskStore tasks, ReportReviewStore reviews, ReportReviewService reviewService,
                                  ReportService reports, ResearchMaterials materials) {
        this.tasks = tasks;
        this.reviews = reviews;
        this.reviewService = reviewService;
        this.reports = reports;
        this.materials = materials;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public History history(ActorContext actor, UUID reportId) {
        context(actor, reportId, false);
        return new History(tasks.versions(reportId), tasks.results(reportId));
    }

    public TaskVersion version(ActorContext actor, UUID reportId, UUID versionId) {
        context(actor, reportId, false);
        return tasks.version(reportId, versionId).orElseThrow(EngineeringTaskService::notFound);
    }

    public Optional<TaskResult> resultForVerification(ActorContext actor, UUID reportId, UUID verificationVersionId) {
        context(actor, reportId, false);
        return tasks.result(reportId, verificationVersionId);
    }

    @Transactional
    public TaskVersion save(ActorContext actor, UUID reportId, TaskCommand cmd) {
        Context c = context(actor, reportId, true);
        if (cmd == null || cmd.requestId() == null || cmd.fields() == null) throw invalidRequest();
        TaskFields fields = validate(cmd.fields());
        var repeated = tasks.versionRequest(reportId, cmd.requestId());
        if (repeated.isPresent()) {
            var first = repeated.get();
            if (!Objects.equals(first.previousVersionId(), cmd.previousVersionId()) || !first.fields().equals(fields)) {
                throw reused();
            }
            return first;
        }
        UUID id = UUID.randomUUID(), taskId = id;
        int number = 1;
        if (cmd.previousVersionId() != null) {
            var old = tasks.version(reportId, cmd.previousVersionId()).orElseThrow(EngineeringTaskService::notFound);
            if (tasks.hasRevision(reportId, old.id())) {
                throw conflict("该任务版本已有更正，请刷新并以最新任务版本继续。");
            }
            taskId = old.taskId();
            number = old.version() + 1;
        }
        var report = reports.find(actor, reportId);
        int line = excerptLine(report, fields);
        var baseline = frozenBaseline(c, report);
        materials.require(actor, fields.uploadIds());
        tasks.insert(c, actor, id, taskId, cmd.requestId(), cmd.previousVersionId(), number,
                baseline.id(), baseline.version(), line, fields);
        return tasks.version(reportId, id).orElseThrow();
    }

    @Transactional
    public TaskResult record(ActorContext actor, UUID reportId, UUID taskVersionId, ResultCommand cmd, PatchFile file) {
        context(actor, reportId, true);
        var task = tasks.version(reportId, taskVersionId).orElseThrow(EngineeringTaskService::notFound);
        if (cmd == null || cmd.requestId() == null || cmd.status() == null) throw invalidRequest();

        var fields = ReportReviewService.validate(new VerificationInput(task.fields().question(), task.fields().excerpt(),
                task.fields().origin(), cmd.status(), cmd.method(), cmd.environment(), cmd.observation(), cmd.impact(),
                cmd.limits(), cmd.blocker(), cmd.uploadIds(), cmd.baselineBuildId(), cmd.candidateBuildId(), cmd.executedBy()));
        boolean executed = cmd.status() == Execution.EXECUTED;
        Instant when = cmd.executedAt() == null ? null : cmd.executedAt().truncatedTo(ChronoUnit.MICROS);
        if (executed && (fields.executedBy() == null || when == null)) {
            throw new IllegalArgumentException("已执行的结果需要实际执行者和实际执行时间。");
        }
        if (!executed && (when != null || file != null || cmd.retainPreviousPatch())) {
            throw new IllegalArgumentException("实际执行时间和返回补丁只能记在已执行的结果中。");
        }
        String source = text(cmd.sourceRevision(), 1000, executed);
        String candidate = text(cmd.candidateRevision(), 1000, false);
        String unfinished = text(cmd.unfinished(), 2000, false);

        TaskResult previous = null;
        if (cmd.previousVerificationVersionId() != null) {
            previous = tasks.result(reportId, cmd.previousVerificationVersionId()).orElseThrow(EngineeringTaskService::notFound);
            if (!taskVersionId.equals(previous.taskVersionId())) {
                throw conflict("结果更正须沿用原结果依照的确切任务版本。");
            }
        }
        var patch = resultPatch(reportId, cmd, previous, file);
        var repeated = tasks.resultRequest(reportId, cmd.requestId());
        if (repeated.isPresent()) {
            var first = repeated.get();
            var verification = reviews.verification(reportId, first.verificationVersionId()).orElseThrow();
            if (!taskVersionId.equals(first.taskVersionId()) || !Objects.equals(when, first.executedAt())
                    || !source.equals(first.sourceRevision()) || !candidate.equals(first.candidateRevision())
                    || !unfinished.equals(first.unfinished()) || !Objects.equals(patch.info(), first.patch())
                    || !Objects.equals(cmd.previousVerificationVersionId(), verification.previousVersionId())
                    || !fields.equals(verification.fields())) throw reused();
            return first;
        }
        // A private request ID keeps a stand-alone verification submission from claiming the result's receipt.
        var verified = reviewService.recordForTask(actor, reportId,
                new ReportReviewService.VerificationCommand(UUID.randomUUID(), cmd.previousVerificationVersionId(), fields));
        tasks.addResult(reportId, taskVersionId, verified.id(), cmd.requestId(), actor, when, source, candidate,
                unfinished, patch.info(), patch.bytes());
        return tasks.result(reportId, verified.id()).orElseThrow();
    }

    public byte[] patch(ActorContext actor, UUID reportId, UUID verificationVersionId) {
        var result = resultForVerification(actor, reportId, verificationVersionId).orElseThrow(EngineeringTaskService::notFound);
        if (result.patch() == null) throw notFound();
        return checkedBytes(reportId, result);
    }

    private PreparedPatch resultPatch(UUID reportId, ResultCommand cmd, TaskResult previous, PatchFile file) {
        if (!cmd.retainPreviousPatch()) {
            return file == null ? new PreparedPatch(null, null) : validatePatch(file);
        }
        if (file != null || previous == null || previous.patch() == null) {
            throw new IllegalArgumentException("只有更正含补丁的结果且没有选择新文件时才能沿用原补丁。");
        }
        return new PreparedPatch(previous.patch(), checkedBytes(reportId, previous));
    }

    private byte[] checkedBytes(UUID reportId, TaskResult result) {
        byte[] bytes = tasks.patchBytes(reportId, result.verificationVersionId())
                .orElseThrow(() -> conflict("该版本的补丁原件缺失。"));
        if (bytes.length != result.patch().size() || !hash(bytes).equals(result.patch().sha256())) {
            throw conflict("补丁原件与保存的大小或哈希不一致。");
        }
        return bytes;
    }

    private static TaskFields validate(TaskFields f) {
        if (f.origin() == null || f.readiness() == null) throw new IllegalArgumentException("请选择建议来源和任务就绪状态。");
        boolean ready = f.readiness() == Readiness.READY;
        String missing = text(f.missingMaterials(), 2000, !ready);
        if (ready && !missing.isEmpty()) throw new IllegalArgumentException("仍有材料待补时，请把任务标为待补资料。");
        String originDetail = text(f.originDetail(), 200, false);
        if (f.origin() == Origin.MODEL && !originDetail.isEmpty()) {
            throw new IllegalArgumentException("原模型信息取自已保存报告，人工或外部工具来源可另行注明。");
        }
        return new TaskFields(f.origin(), originDetail, lines(text(f.excerpt(), 2000, true)),
                f.readiness(), text(f.question(), 1000, true), text(f.deliverable(), 2000, ready),
                text(f.inScope(), 2000, ready), text(f.outOfScope(), 2000, ready),
                text(f.facts(), 2000, false), text(f.inferences(), 2000, false), text(f.unknowns(), 2000, false),
                text(f.method(), 2000, ready), text(f.completion(), 2000, ready), text(f.stopConditions(), 2000, ready),
                text(f.environment(), 2000, ready), missing, ids(f.verificationVersionIds(), 50), ids(f.uploadIds(), 10));
    }

    private static List<UUID> ids(List<UUID> values, int max) {
        if (values == null) return List.of();
        if (values.size() > max || values.stream().anyMatch(Objects::isNull) || values.stream().distinct().count() != values.size()) {
            throw new IllegalArgumentException("所选依据或资料无效，请重新选择。");
        }
        return values.stream().sorted(Comparator.comparing(UUID::toString)).toList();
    }

    private static int excerptLine(ReportRecord report, TaskFields fields) {
        String markdown = lines(report.markdown());
        String excerpt = fields.excerpt();
        int offset = markdown.indexOf(excerpt);
        if (fields.origin() == Origin.MODEL) {
            String answer = lines(report.research().answer());
            int answerOffset = markdown.indexOf(answer);
            int withinAnswer = answer.indexOf(excerpt);
            if (answerOffset < 0 || withinAnswer < 0) {
                throw new IllegalArgumentException("标记为原模型建议的摘录必须来自本报告保存的模型回答。");
            }
            offset = answerOffset + withinAnswer;
        }
        if (offset < 0) throw new IllegalArgumentException("请从已保存报告复制连续原文作为出处，并将补充建议写入任务内容。");
        return 1 + Math.toIntExact(markdown.substring(0, offset).chars().filter(c -> c == '\n').count());
    }

    private static String lines(String value) {
        return Objects.toString(value, "").replace("\r\n", "\n").replace('\r', '\n');
    }

    private static FrozenBaseline frozenBaseline(Context c, ReportRecord report) {
        Map<String, Object> inputs = report.research().inputs();
        if (!inputs.containsKey("engineeringBaseline")) return new FrozenBaseline(null, null);
        if (inputs.get("engineeringBaseline") instanceof Map<?,?> snapshot
                && snapshot.get("baseline") instanceof Map<?,?> baseline && baseline.get("version") instanceof Integer version
                && version > 0 && c.systemId().toString().equals(baseline.get("systemId"))
                && baseline.get("id") instanceof String id) {
            try {
                UUID uuid = UUID.fromString(id);
                if (uuid.toString().equals(id)) return new FrozenBaseline(uuid, version);
            } catch (IllegalArgumentException ignored) {
                // Fail closed for a malformed archived association.
            }
        }
        throw conflict("原报告的工程基线标识或版本无法核对，不能用其他版本替代。");
    }

    private static PreparedPatch validatePatch(PatchFile file) {
        String name = file.name();
        byte[] bytes = file.bytes();
        if (name == null || name.length() > 180 || name.contains("/") || name.contains("\\") || name.contains(":")
                || name.chars().anyMatch(Character::isISOControl)
                || !(name.toLowerCase(Locale.ROOT).endsWith(".patch") || name.toLowerCase(Locale.ROOT).endsWith(".diff"))) {
            throw new IllegalArgumentException("请选择文件名有效的 .patch 或 .diff 补丁。");
        }
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_PATCH_BYTES) {
            throw new IllegalArgumentException("补丁需为 1 字节到 1 MiB 的 UTF-8 文本。");
        }
        try {
            var decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));
            if (decoded.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
                throw new IllegalArgumentException("补丁含不支持的控制字符。");
            }
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("补丁须为 UTF-8 文本。");
        }
        return new PreparedPatch(new PatchInfo(name, bytes.length, hash(bytes)), bytes);
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Context context(ActorContext actor, UUID reportId, boolean lock) {
        return reviews.context(actor, reportId, lock).orElseThrow(EngineeringTaskService::notFound);
    }

    private static IllegalArgumentException invalidRequest() { return new IllegalArgumentException("缺少提交标识或内容。"); }
    private static ResponseStatusException reused() { return conflict("此提交标识已用于其他内容，请核对首次保存的版本。"); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到本报告的任务或执行依据。"); }
}
