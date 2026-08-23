package com.jundaodsj.insightops.server.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.agent.application.AgentRunQuery;
import com.jundaodsj.insightops.agent.application.AgentWorkflowRunStore;
import com.jundaodsj.insightops.agent.application.AgentWorkflowTemplateStore;
import com.jundaodsj.insightops.conversation.application.ChatRunStore;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.server.chat.DurableChatRunCoordinator;
import com.jundaodsj.insightops.tool.application.registry.AgentToolDefinition;
import com.jundaodsj.insightops.tool.application.registry.AgentToolRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AgentWorkflowRunService {

    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() { };
    private final AgentWorkflowTemplateStore templates;
    private final AgentWorkflowRunStore workflowRuns;
    private final ChatRunStore chatRuns;
    private final AgentRunQuery runQuery;
    private final DurableChatRunCoordinator durableRuns;
    private final AgentWorkflowExpressionService expressions;
    private final AgentToolRegistry registry;
    private final ObjectMapper json;

    public AgentWorkflowRunService(
            AgentWorkflowTemplateStore templates,
            AgentWorkflowRunStore workflowRuns,
            ChatRunStore chatRuns,
            AgentRunQuery runQuery,
            DurableChatRunCoordinator durableRuns,
            AgentWorkflowExpressionService expressions,
            AgentToolRegistry registry,
            ObjectMapper json) {
        this.templates = templates;
        this.workflowRuns = workflowRuns;
        this.chatRuns = chatRuns;
        this.runQuery = runQuery;
        this.durableRuns = durableRuns;
        this.expressions = expressions;
        this.registry = registry;
        this.json = json;
    }

    public List<ActiveTemplate> activeTemplates(UUID workspaceId) {
        return templates.overview(workspaceId).stream()
                .filter(item -> "ACTIVE".equals(item.status()) && item.activeVersionId() != null)
                .map(item -> {
                    AgentWorkflowTemplateStore.WorkflowVersion version = item.versions().stream()
                            .filter(candidate -> candidate.id().equals(item.activeVersionId()))
                            .findFirst().orElseThrow();
                    AgentWorkflowExpressionService.Graph graph = expressions.validateGraph(version.graphSpecJson());
                    return new ActiveTemplate(
                            item.id(), item.name(), item.description(), item.category(),
                            version.id(), version.version(), version.summary(), version.entryQuestion(),
                            graph.inputs(), version.graphSpecJson());
                }).toList();
    }

    @Transactional
    public LaunchResult launch(
            ActorContext actor, boolean systemAdmin, AgentToolDefinition.AccessLevel accessLevel,
            UUID templateId, UUID expectedVersionId, UUID sessionId,
            UUID requestId, Map<String, Object> suppliedInputs, String traceId) {
        AgentWorkflowRunStore.WorkflowRun existing = workflowRuns.findByRequest(
                actor.workspaceId(), actor.userId(), requestId).orElse(null);
        if (existing != null) return new LaunchResult(existing.runId(), null, true);

        AgentWorkflowTemplateStore.WorkflowTemplate template = templates.find(actor.workspaceId(), templateId)
                .orElseThrow(() -> new WorkflowRunException("WORKFLOW_TEMPLATE_NOT_FOUND"));
        if (!"ACTIVE".equals(template.status()) || template.activeVersionId() == null) {
            throw new WorkflowRunException("WORKFLOW_TEMPLATE_NOT_ACTIVE");
        }
        if (!template.activeVersionId().equals(expectedVersionId)) {
            throw new WorkflowRunException("WORKFLOW_ACTIVE_VERSION_CHANGED");
        }
        AgentWorkflowTemplateStore.WorkflowVersion version = template.versions().stream()
                .filter(item -> item.id().equals(expectedVersionId)).findFirst()
                .orElseThrow(() -> new WorkflowRunException("WORKFLOW_VERSION_NOT_FOUND"));
        AgentWorkflowExpressionService.Graph graph = expressions.validateGraph(version.graphSpecJson());
        validateAccess(graph, accessLevel);
        Map<String, Object> inputs = expressions.validateInputs(graph, suppliedInputs);
        String question = expressions.resolveText(version.entryQuestion(), inputs);
        Instant now = Instant.now();
        UUID runId = UUID.randomUUID();
        UUID actualSessionId = chatRuns.startRun(
                actor, runId, sessionId, traceId, question, now);
        workflowRuns.create(new AgentWorkflowRunStore.WorkflowRunDraft(
                        runId, actor.workspaceId(), actor.userId(), template.id(), version.id(),
                        template.name(), version.version(), question, graph.root().toString(),
                        write(inputs), expressions.contractFingerprint(graph), requestId,
                        null, runId, null, now), nodeDrafts(graph, null, now));
        durableRuns.enqueue(actor, runId, actualSessionId, traceId, systemAdmin,
                accessLevel, question, question, null, now);
        return new LaunchResult(runId, actualSessionId, false);
    }

    @Transactional
    public LaunchResult retry(
            ActorContext actor, boolean systemAdmin, AgentToolDefinition.AccessLevel accessLevel,
            UUID sourceRunId, String fromNodeId, UUID requestId, String traceId) {
        AgentWorkflowRunStore.WorkflowRun existing = workflowRuns.findByRequest(
                actor.workspaceId(), actor.userId(), requestId).orElse(null);
        if (existing != null) return new LaunchResult(existing.runId(), null, true);
        AgentRunQuery.RunDetail sourceDetail = runQuery.findRun(actor, sourceRunId)
                .orElseThrow(() -> new WorkflowRunException("WORKFLOW_RUN_NOT_FOUND"));
        if (!"FAILED".equals(sourceDetail.status())) {
            throw new WorkflowRunException("WORKFLOW_RUN_NOT_FAILED");
        }
        AgentWorkflowRunStore.WorkflowRun source = workflowRuns.find(sourceRunId)
                .filter(item -> item.workspaceId().equals(actor.workspaceId())
                        && item.ownerUserId().equals(actor.userId()))
                .orElseThrow(() -> new WorkflowRunException("WORKFLOW_RUN_NOT_FOUND"));
        if (source.nodes().stream().noneMatch(item -> item.logicalNodeId().equals(fromNodeId)
                && "FAILED".equals(item.status()))) {
            throw new WorkflowRunException("WORKFLOW_RETRY_NODE_INVALID");
        }
        AgentWorkflowExpressionService.Graph graph = expressions.validateGraph(source.graphSpecJson());
        validateAccess(graph, accessLevel);
        Instant now = Instant.now();
        UUID runId = UUID.randomUUID();
        UUID sessionId = chatRuns.startRun(
                actor, runId, sourceDetail.sessionId(), traceId,
                source.entryQuestion() + "（从失败节点 " + fromNodeId + " 重试）", now);
        workflowRuns.create(new AgentWorkflowRunStore.WorkflowRunDraft(
                        runId, actor.workspaceId(), actor.userId(), source.templateId(),
                        source.templateVersionId(), source.templateName(), source.templateVersion(),
                        source.entryQuestion(), source.graphSpecJson(), source.inputJson(),
                        source.toolContractFingerprint(), requestId, sourceRunId,
                        source.retryRootRunId() == null ? sourceRunId : source.retryRootRunId(),
                        fromNodeId, now), nodeDrafts(graph, source, now));
        durableRuns.enqueue(actor, runId, sessionId, traceId, systemAdmin, accessLevel,
                source.entryQuestion(), source.entryQuestion(), null, now);
        return new LaunchResult(runId, sessionId, false);
    }

    private List<AgentWorkflowRunStore.NodeDraft> nodeDrafts(
            AgentWorkflowExpressionService.Graph graph,
            AgentWorkflowRunStore.WorkflowRun source, Instant now) {
        Map<String, AgentWorkflowRunStore.WorkflowNode> sourceNodes = source == null ? Map.of()
                : source.nodes().stream().collect(java.util.stream.Collectors.toMap(
                        AgentWorkflowRunStore.WorkflowNode::logicalNodeId, item -> item));
        List<AgentWorkflowRunStore.NodeDraft> drafts = new ArrayList<>();
        for (AgentWorkflowExpressionService.NodeDefinition node : graph.nodes()) {
            AgentWorkflowRunStore.WorkflowNode previous = sourceNodes.get(node.id());
            boolean reused = previous != null && previous.reusable();
            drafts.add(new AgentWorkflowRunStore.NodeDraft(
                    UUID.randomUUID(), node.id(), node.toolName(), node.toolVersion(), node.riskLevel(),
                    node.required(), node.condition(), write(node.dependsOn()), node.arguments().toString(),
                    write(node.exposeOutputs()), reused ? "REUSED" : "PENDING",
                    reused ? previous.id() : null, reused ? previous.resolvedInputJson() : null,
                    reused ? previous.outputJson() : null, reused ? previous.exposedOutputJson() : null,
                    reused ? previous.promptAppendix() : null,
                    reused ? previous.sourceUrlsJson() : "[]", reused ? previous.citationsJson() : "[]",
                    reused ? previous.attemptCount() : 0,
                    reused ? previous.inputTokens() : 0, reused ? previous.outputTokens() : 0,
                    reused ? previous.estimatedCostCny() : BigDecimal.ZERO,
                    reused ? previous.errorCode() : null,
                    reused ? previous.startedAt() : null, reused ? previous.finishedAt() : null, now));
        }
        return List.copyOf(drafts);
    }

    private void validateAccess(
            AgentWorkflowExpressionService.Graph graph,
            AgentToolDefinition.AccessLevel accessLevel) {
        Set<String> allowed = registry.availableTo(accessLevel).stream()
                .map(AgentToolDefinition::name).collect(java.util.stream.Collectors.toSet());
        if (graph.nodes().stream().anyMatch(node -> !allowed.contains(node.toolName()))) {
            throw new WorkflowRunException("WORKFLOW_TOOL_NOT_ALLOWED");
        }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) {
            throw new WorkflowRunException("WORKFLOW_SERIALIZATION_FAILED", exception);
        }
    }

    public record ActiveTemplate(
            UUID id, String name, String description, String category,
            UUID activeVersionId, int version, String summary, String entryQuestion,
            Map<String, AgentWorkflowExpressionService.InputDefinition> inputs,
            String graphSpecJson) { }
    public record LaunchResult(UUID runId, UUID sessionId, boolean duplicate) { }

    public static final class WorkflowRunException extends IllegalArgumentException {
        public WorkflowRunException(String message) { super(message); }
        public WorkflowRunException(String message, Throwable cause) { super(message, cause); }
    }
}
