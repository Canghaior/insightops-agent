package com.jundaodsj.insightops.server.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.businesssystem.application.EngineeringBaselineStore;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.report.application.ReportDeliveryStore.ReportRecord;
import com.jundaodsj.insightops.report.application.ReportReviewStore.Context;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.UUID;

/** Only the report's frozen reference authorizes which version to resolve for an export. */
@Component
public class FrozenBaselineHandoff {
    private final EngineeringBaselineStore baselines;
    private final ObjectMapper json;

    public FrozenBaselineHandoff(EngineeringBaselineStore baselines, ObjectMapper json) {
        this.baselines = baselines;
        this.json = json;
    }

    record Original(UUID id, UUID systemId, int version, String path, String originalName, long size, String sha256) {
        String overview() {
            return "\n## 原报告采用的工程基线\n\n"
                    + "业务系统 " + systemId + "，工程基线 v" + version + "（" + id + "）。"
                    + "查看[冻结的工程事实](report/engineering-baseline.json)及[该版本原件](<" + path + ">)。"
                    + "原件共 " + size + " 字节，SHA-256 为 `" + sha256 + "`。\n\n"
                    + "外部执行者应另行确认实际源码、候选补丁和执行环境。哈希仅核对交付文件的字节，不能认证提交来源。"
                    + "验证记录采用的基线和候选构建批次由该条验证版本的构建引用确定。\n";
        }
    }

    Original write(HandoffArchiveWriter writer, ActorContext actor, Context context, ReportRecord report) throws IOException {
        var research = report.research();
        if (research == null || research.inputs() == null || !research.inputs().containsKey("engineeringBaseline")) {
            return null;
        }
        JsonNode frozen = json.valueToTree(research.inputs().get("engineeringBaseline"));
        var meta = frozen.path("baseline");
        var content = frozen.path("content");
        UUID id = requiredId(meta.path("id"));
        UUID systemId = requiredId(meta.path("systemId"));
        var version = meta.path("version");
        String name = content.path("originalName").asText("");
        String hash = content.path("originalSha256").asText("");
        if (!systemId.equals(context.systemId()) || !context.reportId().equals(report.id())
                || !context.runId().equals(research.runId()) || !version.isIntegralNumber()
                || !version.canConvertToInt() || version.intValue() < 1 || name.isBlank()
                || !hash.matches("[0-9a-f]{64}")) {
            throw conflict("原报告冻结的工程基线归属、版本或原件标识不完整。");
        }

        var saved = baselines.find(actor, systemId, id).orElseThrow(FrozenBaselineHandoff::missing);
        if (!id.equals(saved.baseline().id()) || !systemId.equals(saved.baseline().systemId())
                || saved.baseline().version() != version.intValue() || !hash.equals(saved.content().originalSha256())
                || !name.equals(saved.content().originalName())) {
            throw conflict("已存工程原件与原报告冻结的版本或 SHA-256 不一致。");
        }
        var bytes = baselines.original(actor, systemId, id).orElseThrow(FrozenBaselineHandoff::missing);
        String leaf = HandoffArchiveWriter.leaf(name, name.equalsIgnoreCase("pom.xml") ? "pom.xml" : "baseline.zip");
        String path = "engineering-baseline/" + id + "/" + leaf;
        writer.original(path, "FROZEN_BASELINE_ORIGINAL", id, name, bytes.length, hash, new ByteArrayInputStream(bytes));
        writer.json("report/engineering-baseline.json", "FROZEN_REPORT_BASELINE", id, frozen);
        writer.markdown("report/baseline-scope.md", "BASELINE_SCOPE", id,
                "本报告采用工程基线 v" + version.intValue() + "（" + id + "）。"
                        + "对应原件存于 `" + path + "`。\n\n外部执行采用的源码和环境由执行者记录。"
                        + "验证的基线／候选构建分别由验证版本指定，可能与报告取得工程资料时的环境不同。\n");
        return new Original(id, systemId, version.intValue(), path, name, bytes.length, hash);
    }

    private static UUID requiredId(JsonNode node) {
        try {
            return UUID.fromString(node.asText(""));
        } catch (IllegalArgumentException ignored) {
            throw conflict("原报告工程基线的固定标识缺失或无效。");
        }
    }

    private static ResponseStatusException missing() {
        return conflict("原报告对应的工程基线或原件已缺失，或当前账号无法取得该版本。");
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason + "交接包未生成。");
    }
}
