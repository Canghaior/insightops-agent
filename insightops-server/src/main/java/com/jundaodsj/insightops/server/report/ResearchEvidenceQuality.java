package com.jundaodsj.insightops.server.report;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.agent.application.AgentRunQuery;
import com.jundaodsj.insightops.businesssystem.application.AssessmentType;
import com.jundaodsj.insightops.agent.application.AgentWorkflowRunStore;
import com.jundaodsj.insightops.report.application.ResearchEvidenceAssessment;
import com.jundaodsj.insightops.report.application.ResearchEvidenceAssessment.Coverage;
import com.jundaodsj.insightops.report.application.ResearchEvidenceAssessment.Reason;
import com.jundaodsj.insightops.report.application.ResearchEvidenceAssessment.Section;
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
        boolean decision = AssessmentType.SYSTEM_DECISION.name()
                .equals(inputs.get("assessmentType")) && inputs.containsKey("systemSnapshot");
        boolean hasBaseline = inputs.get("engineeringBaseline") instanceof Map<?, ?>;
        String target = Objects.toString(inputs.get("targetVersion"), "").strip();
        String current = Objects.toString(inputs.get("currentVersion"), "").strip();
        String project = Objects.toString(inputs.get("projectName"), "").strip();
        boolean noExternalSelection = decision && !inputs.containsKey("projectId")
                && inputs.get("uploadIds") instanceof List<?> files && files.isEmpty();
        boolean engineeringOnly = hasBaseline && noExternalSelection && target.isEmpty();
        boolean projectAssessment = decision || !target.isEmpty();
        Map<String, Reason> engineeringReasons = new LinkedHashMap<>();
        Map<String, Reason> externalReasons = new LinkedHashMap<>();
        Map<String, Reason> toolReasons = engineeringOnly ? engineeringReasons : externalReasons;
        boolean insufficient = false;
        for (var tool : run.toolCalls()) {
            if (!"SUCCEEDED".equals(tool.status())) {
                add(toolReasons, "TOOL_INCOMPLETE", tool.toolName() + " 未成功完成，相关结论缺少该工具证据。");
                insufficient = true;
            }
            JsonNode output = json.valueToTree(tool.resultPayload());
            if (output == null || !output.isObject()) {
                add(toolReasons, "TOOL_RESULT_UNRECORDED", tool.toolName() + " 未保存可检查的结果，不能确认其证据完整性。");
                insufficient = true;
                continue;
            }
            if (output.has("answerable") && !output.path("answerable").asBoolean()) {
                add(toolReasons, "RETRIEVAL_EMPTY", "已检索所选范围，未取得可引用的对应片段。"); insufficient = true;
            }
            JsonNode range = output.path("range");
            if (!target.isBlank() && "github_release_list".equals(tool.toolName())
                    && (!range.path("complete").isBoolean() || !range.path("currentVersionFound").isBoolean()
                    || !range.path("targetVersionFound").isBoolean())) {
                add(toolReasons, "VERSION_RANGE_UNRECORDED", "没有完整记录 Release 范围及两端版本检查结果，不能默认版本资料齐全。");
                insufficient = true;
            }
            if (output.path("truncated").asBoolean() || (range.has("complete") && !range.path("complete").asBoolean())) {
                add(toolReasons, "VERSION_RANGE_INCOMPLETE", "Release 扫描或返回范围不完整，不能确认已覆盖全部版本变化。"); insufficient = true;
            }
            for (String field : List.of("currentVersionFound", "targetVersionFound")) {
                if (range.has(field) && !range.path(field).asBoolean()) {
                    String endpoint = field.startsWith("current") ? "起点 " + current : "目标版本 " + target;
                    add(toolReasons, "VERSION_ENDPOINT_MISSING", "未确认" + endpoint.stripTrailing() + " 的 Release。"); insufficient = true;
                }
            }
            if (output.has("vectorAvailable") && !output.path("vectorAvailable").asBoolean())
                add(toolReasons, "KEYWORD_FALLBACK", "本次降级为关键词检索，需检查是否遗漏相关资料。");
            if (output.path("warnings").isArray()) output.path("warnings").forEach(warning -> {
                if (warning.isTextual() && !warning.asText().isBlank()) add(toolReasons, "SOURCE_WARNING", warning.asText());
            });
            if ("github_issue_read".equals(tool.toolName()))
                add(toolReasons, "ISSUE_SCOPE_LIMITED", "Issue 仅读取正文和状态，未核查评论或关联 PR；关闭状态不能证明目标版本已包含修复。");
        }
        for (var node : nodes) {
            if (Set.of("FAILED", "CANCELLED").contains(node.status()) || (node.required() && !node.reusable())) {
                add(toolReasons, "WORKFLOW_EVIDENCE_MISSING", "研究步骤 " + node.logicalNodeId() + " 未完成，相关证据存在缺口。"); insufficient = true;
            }
        }
        boolean engineeringMissing = engineeringOnly && insufficient;
        if (engineeringOnly) {
            add(engineeringReasons, "ENGINEERING_BASELINE_ONLY", "分析范围为所选工程基线中的事实和资料。");
        } else if (noExternalSelection && !hasBaseline) {
            add(externalReasons, "NO_EVIDENCE_SELECTED", "本次未选框架或资料，没有外部资料依据。请优先补充与问题直接相关的记录。");
            insufficient = true;
        }
        if (!engineeringOnly && run.citationDetails().isEmpty()) {
            add(externalReasons, "CITATIONS_MISSING", projectAssessment
                    ? "没有可核对的外部引用。依赖外部资料的判断仍待查证。"
                    : "没有结构化引用，无法将结论与来源逐项核对。");
            insufficient = true;
        }
        Set<String> labels = new HashSet<>();
        run.citationDetails().forEach(citation -> labels.add(citation.label()));
        var matched = CITATION.matcher(run.answer());
        boolean hasCitation = false;
        while (matched.find()) {
            // Spring release milestone labels in Markdown are version names, not citation identifiers.
            if (!labels.contains(matched.group(1)) && matched.group(1).matches("(?i)(M|RC)[0-9]+")) continue;
            hasCitation = true;
            if (!labels.contains(matched.group(1))) {
                add(externalReasons, "UNKNOWN_CITATION", "回答引用了不存在的来源编号 " + matched.group(1) + "。"); insufficient = true;
            }
        }
        if (!hasCitation && !(decision && run.citationDetails().isEmpty())) {
            add(externalReasons, "ANSWER_CITATIONS_MISSING", "已取得来源，但回答正文没有引用编号。请核对关键判断与来源的对应关系。"); insufficient = true;
        }
        if (!target.isBlank()) {
            var targetVersion = version(target);
            var currentVersion = version(current);
            if (targetVersion == null || currentVersion == null) {
                add(externalReasons, "VERSION_FORMAT_UNCONFIRMED", "当前或目标版本格式无法识别，版本范围尚未核实。");
                insufficient = true;
            }
            boolean targetEvidence = false;
            Set<String> sourceVersions = new LinkedHashSet<>();
            for (var citation : run.citationDetails()) {
                var sourceVersion = version(citation.versionLabel());
                if (sourceVersion == null) {
                    add(externalReasons, "SOURCE_VERSION_UNCONFIRMED", "部分资料未标注可识别的固定版本，需核对其是否适用于目标版本。");
                    continue;
                }
                sourceVersions.add(citation.versionLabel());
                // A branch label such as 3.5 is not evidence for the exact patch 3.5.0.
                if (targetVersion != null && sourceVersion.equals(targetVersion)
                        && citation.versionLabel().matches("(?i)^v?\\d+\\.\\d+\\.\\d+.*$")) targetEvidence = true;
                if (targetVersion != null && sourceVersion.compareTo(targetVersion) > 0)
                    add(externalReasons, "LATER_VERSION_SOURCE", "包含晚于目标版本的资料（" + citation.versionLabel() + "）；只能用于对照，不能据此认定目标版本已有相关修复。");
                if (targetVersion != null && currentVersion != null
                        && !sameBranch(sourceVersion, targetVersion) && !sameBranch(sourceVersion, currentVersion))
                    add(externalReasons, "OTHER_BRANCH_SOURCE", "包含其他维护分支的资料（" + citation.versionLabel() + "）；需另核对相关变更是否合入目标版本。");
            }
            if (!targetEvidence) {
                String found = sourceVersions.isEmpty() ? "当前引用里没有可识别的版本标记。"
                        : "当前引用的版本包括 " + String.join("、", sourceVersions.stream().limit(5).toList()) + "。";
                String framework = project.isEmpty() ? "" : project + " ";
                add(externalReasons, "TARGET_VERSION_EVIDENCE_MISSING", "缺少 " + framework + "目标版本 " + target
                        + " 的可核对来源。" + found + "请补充该版本的官方 Release 或带准确版本标记的迁移资料。");
                insufficient = true;
            }
        }
        var validation = new Section("评估时未执行", target.isEmpty()
                ? "本次评估核对资料。实际代码行为可通过后续验证记录确认。"
                : "升级建议仍需在业务项目中执行构建、测试和关键流程回归。",
                target.isEmpty() ? List.of() : List.of(new Reason("PROJECT_VALIDATION_REQUIRED", "目标环境的兼容性仍待验证。")), false);
        List<Reason> reasons = new ArrayList<>(engineeringReasons.values());
        reasons.addAll(externalReasons.values());
        reasons.addAll(validation.reasons());
        String status = insufficient ? "INSUFFICIENT_EVIDENCE" : projectAssessment || !reasons.isEmpty()
                ? "REQUIRES_VALIDATION" : "REVIEW_READY";
        if (!projectAssessment) return result(status, reasons);
        var baseline = json.valueToTree(inputs.get("engineeringBaseline")).path("baseline");
        String baselineVersion = baseline.path("version").asText("");
        String baselineLabel = hasBaseline ? "已关联工程基线" + (baselineVersion.isEmpty() ? "" : " v" + baselineVersion)
                : "本次未关联基线";
        var engineering = new Section(baselineLabel, hasBaseline
                ? "工程事实可按原件位置核对。报告保留基线的来源与行号。"
                : "本次记录了随评估提交的项目背景。可关联基线以核对工程原件。",
                List.copyOf(engineeringReasons.values()), engineeringMissing);
        boolean externalMissing = insufficient && (!engineeringOnly || !externalReasons.isEmpty());
        String externalLabel = externalMissing ? "缺少依据" : run.citationDetails().isEmpty()
                ? "本次未选用" : "已取得 " + run.citationDetails().size() + " 条可核对来源";
        String externalSummary = !target.isEmpty()
                ? (project.isEmpty() ? "版本评估" : project) + " · " + current + " → " + target + "。需核对对应版本的资料。"
                : engineeringOnly ? "本次使用工程基线。涉及外部框架的判断可补充适用资料。"
                : "依据本次实际取得的资料和引用检查。";
        var external = new Section(externalLabel, externalSummary, List.copyOf(externalReasons.values()), externalMissing);
        return new ResearchEvidenceAssessment(status, label(status), BOUNDARY, reasons, false,
                decision ? "decision-v2" : "upgrade-v2", new Coverage(engineering, external, validation));
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
        return new ResearchEvidenceAssessment(status, label(status), BOUNDARY, reasons, false, "rules-v1", null);
    }

    private static String label(String status) {
        return switch (status) {
            case "INSUFFICIENT_EVIDENCE" -> "证据不足";
            case "REQUIRES_VALIDATION" -> "需要进一步核实";
            case "REVIEW_READY" -> "可进入人工核查";
            default -> "尚未检查";
        };
    }
}
