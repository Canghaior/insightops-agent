package com.jundaodsj.insightops.server.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.agent.application.AgentWorkflowRunStore;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agent-workflows/runs")
public class AgentWorkflowRunController {

    private final AgentWorkflowRunStore store;
    private final ObjectMapper json;

    public AgentWorkflowRunController(AgentWorkflowRunStore store, ObjectMapper json) {
        this.store = store;
        this.json = json;
    }

    @GetMapping("/{runId}")
    public ApiResponse<WorkflowRunView> detail(
            @PathVariable UUID runId, HttpServletRequest request) {
        var actor = CurrentAccount.actor(request);
        AgentWorkflowRunStore.WorkflowRun run = store.find(runId)
                .filter(item -> item.workspaceId().equals(actor.workspaceId())
                        && item.ownerUserId().equals(actor.userId()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Workflow run not found"));
        return new ApiResponse<>((String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE),
                view(run));
    }

    private WorkflowRunView view(AgentWorkflowRunStore.WorkflowRun run) {
        return new WorkflowRunView(
                run.runId(), run.templateId(), run.templateVersionId(), run.templateName(),
                run.templateVersion(), run.entryQuestion(), value(run.graphSpecJson()),
                value(run.inputJson()), run.toolContractFingerprint(), run.sourceRunId(),
                run.retryRootRunId(), run.retryFromNodeId(), run.createdAt(),
                run.nodes().stream().map(this::node).toList());
    }

    private WorkflowNodeView node(AgentWorkflowRunStore.WorkflowNode node) {
        return new WorkflowNodeView(
                node.id(), node.logicalNodeId(), node.toolName(), node.toolVersion(),
                node.riskLevel(), node.required(), node.conditionType(),
                value(node.dependencyNodeIdsJson()), value(node.argumentTemplateJson()),
                value(node.exposeOutputsJson()), value(node.resolvedInputJson()),
                value(node.outputJson()), value(node.exposedOutputJson()), node.status(),
                node.attemptCount(), node.toolCallId(), node.planNodeId(), node.reusedFromNodeId(),
                node.inputTokens(), node.outputTokens(), node.estimatedCostCny(), node.errorCode(),
                node.startedAt(), node.finishedAt(), node.attempts().stream().map(this::attempt).toList());
    }

    private WorkflowAttemptView attempt(AgentWorkflowRunStore.NodeAttemptView item) {
        return new WorkflowAttemptView(
                item.id(), item.attemptNo(), item.runAttempt(), item.workerId(), item.toolCallId(),
                item.status(), value(item.resolvedInputJson()), value(item.outputJson()),
                item.inputTokens(), item.outputTokens(), item.estimatedCostCny(), item.errorCode(),
                item.startedAt(), item.finishedAt());
    }

    private Object value(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return json.readTree(raw); }
        catch (JsonProcessingException exception) { return raw; }
    }

    public record WorkflowRunView(
            UUID runId, UUID templateId, UUID templateVersionId, String templateName,
            int templateVersion, String entryQuestion, Object graphSpec, Object inputs,
            String toolContractFingerprint, UUID sourceRunId, UUID retryRootRunId,
            String retryFromNodeId, Instant createdAt, List<WorkflowNodeView> nodes) { }

    public record WorkflowNodeView(
            UUID id, String logicalNodeId, String toolName, int toolVersion,
            String riskLevel, boolean required, String conditionType,
            Object dependencyNodeIds, Object argumentTemplate, Object exposeOutputs,
            Object resolvedInput, Object output, Object exposedOutput, String status,
            int attemptCount, UUID toolCallId, UUID planNodeId, UUID reusedFromNodeId,
            long inputTokens, long outputTokens, BigDecimal estimatedCostCny,
            String errorCode, Instant startedAt, Instant finishedAt,
            List<WorkflowAttemptView> attempts) { }

    public record WorkflowAttemptView(
            UUID id, int attemptNo, int runAttempt, String workerId, UUID toolCallId,
            String status, Object resolvedInput, Object output, long inputTokens,
            long outputTokens, BigDecimal estimatedCostCny, String errorCode,
            Instant startedAt, Instant finishedAt) { }
}
