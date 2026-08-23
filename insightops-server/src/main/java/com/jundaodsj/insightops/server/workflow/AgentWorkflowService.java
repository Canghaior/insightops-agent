package com.jundaodsj.insightops.server.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.agent.application.AgentWorkflowTemplateStore;
import com.jundaodsj.insightops.agent.application.AgentWorkflowTemplateStore.TemplateDraft;
import com.jundaodsj.insightops.agent.application.AgentWorkflowTemplateStore.VersionDraft;
import com.jundaodsj.insightops.agent.application.AgentWorkflowTemplateStore.WorkflowTemplate;
import com.jundaodsj.insightops.model.application.AgentPlanningModelGateway.PlannedToolCall;
import com.jundaodsj.insightops.server.chat.ConditionalTaskGraph;
import com.jundaodsj.insightops.tool.application.registry.AgentToolDefinition;
import com.jundaodsj.insightops.tool.application.registry.AgentToolRegistry;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AgentWorkflowService {

    private static final int MAX_NODES = 32;
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() { };

    private final AgentWorkflowTemplateStore store;
    private final AgentToolRegistry registry;
    private final ObjectMapper json;

    public AgentWorkflowService(
            AgentWorkflowTemplateStore store, AgentToolRegistry registry, ObjectMapper json) {
        this.store = store;
        this.registry = registry;
        this.json = json;
    }

    public Overview overview(UUID workspaceId, UUID userId) {
        List<WorkflowTemplate> templates = store.overview(workspaceId);
        Set<String> names = new HashSet<>(templates.stream().map(WorkflowTemplate::name).toList());
        for (TemplateDraft item : builtIns()) {
            if (names.contains(item.name())) continue;
            try {
                store.create(workspaceId, userId, validate(item), Instant.now());
            }
            catch (RuntimeException exception) {
                if (store.overview(workspaceId).stream().noneMatch(
                        current -> current.name().equals(item.name()))) {
                    throw exception;
                }
            }
        }
        return new Overview(store.overview(workspaceId), registry.definitions().stream()
                .filter(AgentToolDefinition::enabled).map(ToolSummary::from).toList(), MAX_NODES);
    }

    public WorkflowTemplate create(
            UUID workspaceId, UUID userId, TemplateDraft draft) {
        return store.create(workspaceId, userId, validate(draft), Instant.now());
    }

    public WorkflowTemplate createVersion(
            UUID workspaceId, UUID templateId, UUID userId, VersionDraft draft) {
        return store.createVersion(
                workspaceId, templateId, userId, validate(draft), Instant.now());
    }

    public WorkflowTemplate activate(
            UUID workspaceId, UUID templateId, UUID versionId,
            UUID userId, String reason) {
        WorkflowTemplate template = store.find(workspaceId, templateId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow template not found"));
        var version = template.versions().stream().filter(item -> item.id().equals(versionId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Workflow version not found"));
        preview(version.graphSpecJson());
        return store.activate(workspaceId, templateId, versionId, userId,
                optional(reason, 500), Instant.now());
    }

    public Preview preview(String graphSpecJson) {
        String canonical = canonicalGraph(graphSpecJson);
        ConditionalTaskGraph.Submission submission;
        try {
            submission = ConditionalTaskGraph.parse(
                    new PlannedToolCall("workflow-preview", ConditionalTaskGraph.FUNCTION_NAME, canonical),
                    json, registry, AgentToolDefinition.AccessLevel.SYSTEM_ADMIN, MAX_NODES);
        }
        catch (ConditionalTaskGraph.GraphException exception) {
            throw new IllegalArgumentException(exception.errorCode(), exception);
        }
        Map<UUID, String> names = new LinkedHashMap<>();
        submission.nodes().forEach(node -> names.put(node.id(), node.logicalId()));
        List<NodePreview> nodes = new ArrayList<>();
        for (ConditionalTaskGraph.Node node : submission.nodes()) {
            try {
                Map<String, Object> arguments = json.readValue(node.argumentsJson(), JSON_OBJECT);
                registry.validateInput(
                        node.toolName(), AgentToolDefinition.AccessLevel.SYSTEM_ADMIN, arguments);
            }
            catch (JsonProcessingException | AgentToolRegistry.ToolRegistryException exception) {
                throw new IllegalArgumentException(
                        "WORKFLOW_TOOL_ARGUMENTS_INVALID:" + node.logicalId(), exception);
            }
            nodes.add(new NodePreview(
                    node.logicalId(), node.toolName(), node.argumentsJson(),
                    node.dependencyIds().stream().map(names::get).toList(),
                    node.condition().name(), node.required(), node.riskLevel()));
        }
        List<WavePreview> waves = new ArrayList<>();
        int index = 1;
        for (List<ConditionalTaskGraph.Node> wave : ConditionalTaskGraph.waves(submission.nodes())) {
            waves.add(new WavePreview(index++, wave.stream().map(item -> names.get(item.id())).toList()));
        }
        long mutating = nodes.stream().filter(item -> "MUTATING".equals(item.riskLevel())).count();
        List<String> warnings = new ArrayList<>();
        if (mutating > 0) warnings.add("写工具节点运行时仍会逐项进入人工审批，模板激活不会绕过审批。");
        if (nodes.size() > 8) warnings.add("工作流节点较多，建议在正式运行前检查 Token 与成本预算。");
        int maxParallelism = waves.stream().mapToInt(item -> item.nodeIds().size()).max().orElse(0);
        return new Preview(canonical, submission.reason(), List.copyOf(nodes), List.copyOf(waves),
                nodes.size(), maxParallelism, (int) mutating, List.copyOf(warnings));
    }

    private TemplateDraft validate(TemplateDraft draft) {
        if (draft == null) throw new IllegalArgumentException("Workflow template is required");
        return new TemplateDraft(
                text(draft.name(), "Template name", 128),
                optional(draft.description(), 1_000),
                text(draft.category(), "Template category", 48),
                validate(draft.version()));
    }

    private VersionDraft validate(VersionDraft draft) {
        if (draft == null) throw new IllegalArgumentException("Workflow version is required");
        String question = text(draft.entryQuestion(), "Entry question", 4_000);
        Preview preview = preview(draft.graphSpecJson());
        return new VersionDraft(optional(draft.summary(), 500), question, preview.graphSpecJson());
    }

    private String canonicalGraph(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Workflow graph is required");
        try {
            JsonNode root = json.readTree(value);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("Workflow graph must be a JSON object");
            }
            return json.writeValueAsString(root);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Workflow graph is invalid JSON", exception);
        }
    }

    private List<TemplateDraft> builtIns() {
        if (registry.find("knowledge_hybrid_search").filter(AgentToolDefinition::enabled).isEmpty()) {
            return List.of();
        }
        return List.of(
                builtIn("版本对比", "比较两个版本的官方能力与迁移差异", "VERSION_COMPARISON",
                        "比较目标框架两个版本的变化、兼容性和迁移建议，并给出官方来源。",
                        List.of("检索目标框架旧版本的官方文档和限制",
                                "检索目标框架新版本的官方文档和变更")),
                builtIn("框架选型", "并行收集候选框架证据后形成选型依据", "FRAMEWORK_SELECTION",
                        "比较 Spring AI、LangChain4j 和 Dify 的核心设计、工具编排与适用场景。",
                        List.of("检索 Spring AI 官方架构、工具调用和适用场景",
                                "检索 LangChain4j 官方架构、工具调用和适用场景",
                                "检索 Dify 官方架构、工具调用和适用场景")),
                builtIn("升级影响评估", "从 API、配置和运行风险三个维度评估升级", "UPGRADE_IMPACT",
                        "评估目标框架升级对 API、配置、依赖和生产运行的影响，并给出验证清单。",
                        List.of("检索目标版本 API 和配置变更",
                                "检索目标版本依赖、兼容性和弃用项",
                                "检索目标版本生产升级与回滚建议")),
                builtIn("技术专题研究", "分主题并行检索并输出可追溯研究结论", "TECH_RESEARCH",
                        "围绕一个技术专题，从能力、架构和风险三个角度形成有官方证据的研究报告。",
                        List.of("检索该技术专题的官方核心能力",
                                "检索该技术专题的官方架构与扩展机制",
                                "检索该技术专题的限制、安全和运维风险")));
    }

    private TemplateDraft builtIn(
            String name, String description, String category,
            String question, List<String> queries) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (int i = 0; i < queries.size(); i++) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "research_" + (i + 1));
            node.put("toolName", "knowledge_hybrid_search");
            node.put("arguments", Map.of("query", queries.get(i), "candidateLimit", 12));
            node.put("dependsOn", List.of());
            node.put("condition", "ALWAYS");
            node.put("required", true);
            nodes.add(node);
        }
        return new TemplateDraft(name, description, category,
                new VersionDraft("内置 P2.4-A 模板", question,
                        write(Map.of("reason", description, "nodes", nodes))));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize built-in workflow", exception);
        }
    }

    private static String text(String value, String label, int max) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        String normalized = value.strip();
        if (normalized.length() > max) throw new IllegalArgumentException(label + " is too long");
        return normalized;
    }

    private static String optional(String value, int max) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.strip();
        if (normalized.length() > max) throw new IllegalArgumentException("Text is too long");
        return normalized;
    }

    public record Overview(List<WorkflowTemplate> templates, List<ToolSummary> tools, int maxNodes) { }

    public record ToolSummary(
            String name, String description, String riskLevel,
            String approvalPolicy, Map<String, Object> inputSchema) {
        static ToolSummary from(AgentToolDefinition definition) {
            return new ToolSummary(definition.name(), definition.description(),
                    definition.riskLevel().name(), definition.approvalPolicy().name(),
                    definition.inputSchema());
        }
    }

    public record NodePreview(
            String id, String toolName, String argumentsJson, List<String> dependencyIds,
            String condition, boolean required, String riskLevel) { }

    public record WavePreview(int index, List<String> nodeIds) { }

    public record Preview(
            String graphSpecJson, String reason, List<NodePreview> nodes,
            List<WavePreview> waves, int nodeCount, int maxParallelism,
            int mutatingNodeCount, List<String> warnings) { }
}
