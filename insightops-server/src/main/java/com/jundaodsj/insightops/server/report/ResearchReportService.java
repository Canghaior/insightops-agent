package com.jundaodsj.insightops.server.report;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.agent.application.AgentRunQuery;
import com.jundaodsj.insightops.agent.application.AgentWorkflowRunStore;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.report.application.ReportDeliveryStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
public class ResearchReportService {
    private final AgentRunQuery runs;
    private final AgentWorkflowRunStore workflows;
    private final ReportDeliveryStore reports;
    private final ObjectMapper json;
    private final String publicUrl;

    public ResearchReportService(AgentRunQuery runs, AgentWorkflowRunStore workflows,
                                 ReportDeliveryStore reports, ObjectMapper json,
                                 @Value("${insightops.report.public-base-url:http://127.0.0.1:15173}") String publicUrl) {
        this.runs = runs;
        this.workflows = workflows;
        this.reports = reports;
        this.json = json;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
    }

    public ReportDeliveryStore.ReportRecord create(ActorContext actor, UUID runId) {
        var run = runs.findRun(actor, runId).orElseThrow(ReportService.ReportNotFoundException::new);
        if (!"SUCCEEDED".equals(run.status()) || run.answer() == null || run.answer().isBlank()) {
            throw new IllegalArgumentException("研究尚未成功完成，无法保存报告。");
        }
        var workflow = workflows.find(runId).filter(item -> item.workspaceId().equals(actor.workspaceId())
                && item.ownerUserId().equals(actor.userId())).orElse(null);
        Map<String, Object> inputs = Map.of();
        if (workflow != null) {
            try { inputs = json.readValue(workflow.inputJson(), new TypeReference<>() { }); }
            catch (Exception exception) { throw new IllegalStateException("Unable to read research inputs", exception); }
        }
        List<String> uncertainties = new ArrayList<>();
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (var tool : run.toolCalls()) {
            var output = json.valueToTree(tool.resultPayload());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tool", tool.toolName());
            item.put("status", tool.status());
            item.put("completedAt", tool.finishedAt() == null ? "未完成" : tool.finishedAt().toString());
            for (String field : List.of("fetchedAt", "range", "warnings", "sources")) {
                if (output != null && output.has(field)) item.put(field, json.convertValue(output.get(field), Object.class));
            }
            evidence.add(item);
            if (!"SUCCEEDED".equals(tool.status())) uncertainties.add(tool.toolName() + " 未成功，相关结论缺少该工具证据。");
            if (output != null) {
                if (output.path("truncated").asBoolean()) uncertainties.add("Release 查询受数量或扫描上限限制，版本范围可能不完整。");
                if (output.has("answerable") && !output.path("answerable").asBoolean()) uncertainties.add("知识库未找到足够的对应资料。");
                if (output.has("vectorAvailable") && !output.path("vectorAvailable").asBoolean())
                    uncertainties.add("向量检索不可用，本次使用关键词检索，可能遗漏相关资料。");
                if (output.path("sources").isArray()) output.path("sources").forEach(source -> {
                    if ("未标注".equals(source.path("version").asText()))
                        uncertainties.add("部分文档未标注版本，尚未确认是否适用于目标版本。");
                });
                if (output.path("issue").path("bodyTruncated").asBoolean())
                    uncertainties.add("Issue 正文已截断，完整上下文需返回原文核查。");
                if ("github_issue_read".equals(tool.toolName()))
                    uncertainties.add("已知 Issue 仅读取正文与状态，未读取评论或关联 PR，不能据此确认修复情况。");
                output.path("warnings").forEach(warning -> uncertainties.add(warning.asText()));
            }
        }
        if (workflow != null) for (var node : workflow.nodes()) {
            if (!node.reusable()) uncertainties.add("研究步骤 " + node.logicalNodeId() + " 未成功（" + node.status()
                    + "），相关结论缺少该步骤证据。");
        }
        // Keep the model's uncertainty section verbatim; never replace it with an inferred all-clear.
        var matcher = java.util.regex.Pattern.compile("(?m)^#{1,6}\\s*.*尚未确认事项[^\\n]*\\n").matcher(run.answer());
        if (matcher.find()) uncertainties.add(run.answer().substring(matcher.end()).strip());
        if (run.citationDetails().isEmpty()) uncertainties.add("没有结构化引用，关键事实尚需核查。");
        uncertainties.add("研究基于获取到的资料，未运行你的业务项目验证；不构成兼容性保证。");
        var snapshot = new ReportDeliveryStore.ResearchSnapshot(runId, run.question(), run.answer(), inputs,
                run.citationDetails(), run.sources(), uncertainties.stream().distinct().toList(), evidence,
                run.startedAt() == null ? run.createdAt() : run.startedAt(),
                run.finishedAt() == null ? run.createdAt() : run.finishedAt());
        String title = workflow != null ? workflow.templateName() : "研究报告 · " + run.question();
        if (inputs.containsKey("targetVersion")) title += " · " + inputs.getOrDefault("projectName", "")
                + " " + inputs.getOrDefault("currentVersion", "") + " → " + inputs.get("targetVersion");
        if (title.length() > 200) title = title.substring(0, 200);
        return reports.createResearchReport(actor, UUID.randomUUID(), title, snapshot,
                markdown(title, snapshot), Instant.now());
    }

    private String markdown(String title, ReportDeliveryStore.ResearchSnapshot snapshot) {
        StringBuilder result = new StringBuilder("# ").append(title).append("\n\n")
                .append("研究完成时间：").append(snapshot.finishedAt()).append("\n\n")
                .append(snapshot.answer()).append("\n\n## 研究输入快照\n\n");
        snapshot.inputs().forEach((key, value) -> {
            if ("context".equals(key) && value instanceof Map<?, ?> context) {
                context.forEach((field, fact) -> result.append("- ").append(com.jundaodsj.insightops.server.workflow.ResearchProfiles.label(String.valueOf(field)))
                        .append(": ").append(fact).append('\n'));
            } else if (!List.of("focus", "projectId", "profileId", "researchTerms").contains(key)
                    && !(snapshot.inputs().containsKey("context") && List.of("javaVersion", "springBootVersion").contains(key)))
                result.append("- ").append(inputLabel(key)).append(": ").append(value).append('\n');
        });
        if (snapshot.inputs().containsKey("profileId")) {
            result.append("\n研究目标：评估 ").append(snapshot.inputs().get("projectName")).append(" 从 ")
                    .append(snapshot.inputs().get("currentVersion")).append(" 升级到 ").append(snapshot.inputs().get("targetVersion"))
                    .append(" 对本项目的影响。\n");
        } else result.append("\n原始研究问题：\n\n").append(snapshot.question());
        result.append("\n\n## 来源依据（引用快照）\n\n");
        for (var citation : snapshot.citations()) {
            result.append("- [").append(citation.label()).append("] ").append(citation.title())
                    .append(" · ").append(citation.heading() == null ? "" : citation.heading())
                    .append(" · 资料版本：").append(citation.versionLabel() == null ? "未标注" : citation.versionLabel())
                    .append(" · 获取/采集时间：").append(citation.fetchedAt() == null ? "未记录" : citation.fetchedAt())
                    .append("\n  <").append(url(citation.url())).append(">\n");
        }
        for (String source : snapshot.sources()) {
            if (snapshot.citations().stream().noneMatch(item -> source.equals(item.url()))) {
                result.append("- <").append(url(source)).append(">\n");
            }
        }
        result.append("\n私有或团队资料链接仍需登录原工作区并具备访问权限。\n\n## 尚未确认事项与资料缺口\n\n");
        snapshot.uncertainties().forEach(value -> result.append("- ").append(value).append("\n\n"));
        result.append("## 资料获取记录\n\n");
        snapshot.evidence().forEach(value -> {
            String name = switch (String.valueOf(value.get("tool"))) {
                case "github_release_list" -> "官方 Release";
                case "knowledge_hybrid_search" -> "知识库资料检索";
                case "github_issue_read" -> "已知 Issue";
                default -> String.valueOf(value.get("tool"));
            };
            result.append("- ").append(name).append("：").append("SUCCEEDED".equals(value.get("status")) ? "已取得响应" : "未成功")
                    .append(" · 本次查询时间：").append(value.get("completedAt")).append('\n');
            if (value.containsKey("fetchedAt")) result.append("  远程获取时间：").append(value.get("fetchedAt")).append('\n');
        });
        return result.append("研究记录：").append(publicUrl).append("/runs/").append(snapshot.runId()).append('\n').toString();
    }

    private String url(String value) { return value.startsWith("/api/v1/knowledge/uploads/") ? publicUrl + value : value; }

    private static String inputLabel(String key) {
        return switch (key) {
            case "projectId" -> "关注框架"; case "currentVersion" -> "当前版本";
            case "projectName" -> "关注框架";
            case "profileName" -> "研究方向";
            case "targetVersion" -> "目标版本"; case "javaVersion" -> "Java 版本";
            case "springBootVersion" -> "Spring Boot 版本"; case "businessProject" -> "业务项目";
            case "concerns" -> "关注项"; case "uploadIds" -> "关联资料"; case "issueUrls" -> "已知 Issue";
            default -> key;
        };
    }
}
