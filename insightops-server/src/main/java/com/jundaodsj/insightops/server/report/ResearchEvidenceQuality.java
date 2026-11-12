package com.jundaodsj.insightops.server.report;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.agent.application.AgentRunQuery;
import com.jundaodsj.insightops.agent.application.AgentWorkflowRunStore;
import com.jundaodsj.insightops.report.application.ResearchEvidenceAssessment;
import com.jundaodsj.insightops.report.application.ResearchEvidenceAssessment.Reason;
import com.jundaodsj.insightops.server.workflow.ReleaseVersionRange;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

@Component
public class ResearchEvidenceQuality {
    private static final String BOUNDARY = "自动检查仅核对资料完整性与版本标记；尚未逐条核验结论，也未运行你的业务项目测试，不构成兼容性保证。";
    private static final Pattern CITATION = Pattern.compile("\\[([A-Za-z]+\\d+)]");
    private final ObjectMapper json;

    public ResearchEvidenceQuality(ObjectMapper json) { this.json = json; }

    public ResearchEvidenceAssessment assess(AgentRunQuery.RunDetail run, AgentWorkflowRunStore.WorkflowRun workflow) {
        Map<String, Object> inputs = Map.of();
        if (workflow != null) {
            try { inputs = json.readValue(workflow.inputJson(), new TypeReference<>() { }); }
            catch (Exception exception) {
                return result("NOT_ASSESSED", List.of(new Reason("INPUT_SNAPSHOT_UNREADABLE", "研究输入快照无法读取，暂不能检查版本范围。")));
            }
        }
        return assess(run, inputs, workflow == null ? List.of() : workflow.nodes());
    }

    public ResearchEvidenceAssessment assess(AgentRunQuery.RunDetail run, Map<String, Object> inputs,
                                             List<AgentWorkflowRunStore.WorkflowNode> nodes) {
        if (!"SUCCEEDED".equals(run.status()) || run.answer() == null || run.answer().isBlank()) {
            return result("NOT_ASSESSED", List.of(new Reason("EXECUTION_INCOMPLETE", "尚未形成已完成的研究结果；执行状态不代表证据质量。")));
        }
        Map<String, Reason> reasons = new LinkedHashMap<>();
        boolean insufficient = false;
        String target = String.valueOf(inputs.getOrDefault("targetVersion", ""));
        String current = String.valueOf(inputs.getOrDefault("currentVersion", ""));
        for (var tool : run.toolCalls()) {
            if (!"SUCCEEDED".equals(tool.status())) {
                add(reasons, "TOOL_INCOMPLETE", tool.toolName() + " 未成功完成，相关结论缺少该工具证据。");
                insufficient = true;
            }
            JsonNode output = json.valueToTree(tool.resultPayload());
            if (output == null || !output.isObject()) {
                add(reasons, "TOOL_RESULT_UNRECORDED", tool.toolName() + " 未保存可检查的结果，不能确认其证据完整性。");
                insufficient = true;
                continue;
            }
            if (output.has("answerable") && !output.path("answerable").asBoolean()) {
                add(reasons, "RETRIEVAL_EMPTY", "部分检索未找到足够的对应资料。"); insufficient = true;
            }
            JsonNode range = output.path("range");
            if (!target.isBlank() && "github_release_list".equals(tool.toolName())
                    && (!range.path("complete").isBoolean() || !range.path("currentVersionFound").isBoolean()
                    || !range.path("targetVersionFound").isBoolean())) {
                add(reasons, "VERSION_RANGE_UNRECORDED", "没有完整记录 Release 范围及两端版本检查结果，不能默认版本资料齐全。");
                insufficient = true;
            }
            if (output.path("truncated").asBoolean() || (range.has("complete") && !range.path("complete").asBoolean())) {
                add(reasons, "VERSION_RANGE_INCOMPLETE", "Release 扫描或返回范围不完整，不能确认已覆盖全部版本变化。"); insufficient = true;
            }
            for (String field : List.of("currentVersionFound", "targetVersionFound")) {
                if (range.has(field) && !range.path(field).asBoolean()) {
                    add(reasons, "VERSION_ENDPOINT_MISSING", "未确认版本范围的" + (field.startsWith("current") ? "起点" : "目标") + " Release。"); insufficient = true;
                }
            }
            if (output.has("vectorAvailable") && !output.path("vectorAvailable").asBoolean())
                add(reasons, "KEYWORD_FALLBACK", "本次降级为关键词检索，需检查是否遗漏相关资料。");
            if (output.path("warnings").isArray()) output.path("warnings").forEach(warning -> {
                if (warning.isTextual() && !warning.asText().isBlank()) add(reasons, "SOURCE_WARNING", warning.asText());
            });
            if ("github_issue_read".equals(tool.toolName()))
                add(reasons, "ISSUE_SCOPE_LIMITED", "Issue 仅读取正文和状态，未核查评论或关联 PR；关闭状态不能证明目标版本已包含修复。");
        }
        for (var node : nodes) {
            if (Set.of("FAILED", "CANCELLED").contains(node.status()) || (node.required() && !node.reusable())) {
                add(reasons, "WORKFLOW_EVIDENCE_MISSING", "研究步骤 " + node.logicalNodeId() + " 未完成，相关证据存在缺口。"); insufficient = true;
            }
        }
        if (run.citationDetails().isEmpty()) {
            add(reasons, "CITATIONS_MISSING", "没有结构化引用，无法将结论与来源逐项核对。"); insufficient = true;
        }
        Set<String> labels = new HashSet<>();
        run.citationDetails().forEach(citation -> labels.add(citation.label()));
        var matched = CITATION.matcher(run.answer());
        boolean hasCitation = false;
        while (matched.find()) {
            hasCitation = true;
            if (!labels.contains(matched.group(1))) {
                add(reasons, "UNKNOWN_CITATION", "回答引用了不存在的来源编号 " + matched.group(1) + "。"); insufficient = true;
            }
        }
        if (!hasCitation) {
            add(reasons, "ANSWER_CITATIONS_MISSING", "回答正文没有来源编号；尚不能定位关键结论的依据。"); insufficient = true;
        }
        if (!target.isBlank()) {
            var targetVersion = version(target);
            var currentVersion = version(current);
            if (targetVersion == null || currentVersion == null) {
                add(reasons, "VERSION_FORMAT_UNCONFIRMED", "当前或目标版本格式无法识别，版本范围尚未核实。");
                insufficient = true;
            }
            boolean targetEvidence = false;
            for (var citation : run.citationDetails()) {
                var sourceVersion = version(citation.versionLabel());
                if (sourceVersion == null) {
                    add(reasons, "SOURCE_VERSION_UNCONFIRMED", "部分资料未标注可识别的固定版本，需核对其是否适用于目标版本。");
                    continue;
                }
                // A branch label such as 3.5 is not evidence for the exact patch 3.5.0.
                if (targetVersion != null && sourceVersion.equals(targetVersion)
                        && citation.versionLabel().matches("(?i)^v?\\d+\\.\\d+\\.\\d+.*$")) targetEvidence = true;
                if (targetVersion != null && sourceVersion.compareTo(targetVersion) > 0)
                    add(reasons, "LATER_VERSION_SOURCE", "包含晚于目标版本的资料（" + citation.versionLabel() + "）；只能用于对照，不能据此认定目标版本已有相关修复。");
                if (targetVersion != null && currentVersion != null
                        && !sameBranch(sourceVersion, targetVersion) && !sameBranch(sourceVersion, currentVersion))
                    add(reasons, "OTHER_BRANCH_SOURCE", "包含其他维护分支的资料（" + citation.versionLabel() + "）；需另核对相关变更是否合入目标版本。");
            }
            if (!targetEvidence) {
                add(reasons, "TARGET_VERSION_EVIDENCE_MISSING", "没有找到标注为准确目标版本 " + target + " 的结构化引用。"); insufficient = true;
            }
            add(reasons, "PROJECT_VALIDATION_REQUIRED", "升级建议仍需在业务项目中执行构建、测试和关键流程回归；当前未验证兼容性。");
        }
        return result(insufficient ? "INSUFFICIENT_EVIDENCE" : reasons.isEmpty() ? "REVIEW_READY" : "REQUIRES_VALIDATION", List.copyOf(reasons.values()));
    }

    private static boolean sameBranch(ReleaseVersionRange.Version a, ReleaseVersionRange.Version b) {
        return a.major() == b.major() && a.minor() == b.minor();
    }
    private static ReleaseVersionRange.Version version(String value) {
        try { return ReleaseVersionRange.parse(value); }
        catch (IllegalArgumentException exception) { return null; }
    }
    private static void add(Map<String, Reason> reasons, String code, String message) {
        reasons.putIfAbsent(code + ":" + message, new Reason(code, message));
    }
    private static ResearchEvidenceAssessment result(String status, List<Reason> reasons) {
        String label = switch (status) {
            case "INSUFFICIENT_EVIDENCE" -> "证据不足";
            case "REQUIRES_VALIDATION" -> "需要进一步核实";
            case "REVIEW_READY" -> "可进入人工核查";
            default -> "尚未检查";
        };
        return new ResearchEvidenceAssessment(status, label, BOUNDARY, reasons, false, "rules-v1");
    }
}
