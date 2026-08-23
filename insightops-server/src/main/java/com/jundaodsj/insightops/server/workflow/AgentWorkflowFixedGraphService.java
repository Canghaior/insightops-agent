package com.jundaodsj.insightops.server.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.agent.application.AgentConditionalGraphStore;
import com.jundaodsj.insightops.agent.application.AgentLoopAuditStore;
import com.jundaodsj.insightops.agent.application.AgentOrchestrationStore;
import com.jundaodsj.insightops.agent.application.AgentWorkflowRunStore;
import com.jundaodsj.insightops.conversation.application.ChatCitation;
import com.jundaodsj.insightops.model.application.AgentPlanningModelGateway.PlannedToolCall;
import com.jundaodsj.insightops.model.application.ModelUsage;
import com.jundaodsj.insightops.server.chat.AgentCostGovernanceService;
import com.jundaodsj.insightops.server.chat.AgentCheckpointService;
import com.jundaodsj.insightops.server.chat.AgentLoopService;
import com.jundaodsj.insightops.server.chat.AgentOrchestrationProperties;
import com.jundaodsj.insightops.server.chat.AgentToolDispatcher;
import com.jundaodsj.insightops.server.chat.ConditionalTaskGraph;
import com.jundaodsj.insightops.server.tool.AgentRunExecutionBudget;
import com.jundaodsj.insightops.server.tool.AgentToolResilienceProperties;
import com.jundaodsj.insightops.tool.application.registry.AgentToolDefinition;
import com.jundaodsj.insightops.tool.application.registry.AgentToolRegistry;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

@Service
public class AgentWorkflowFixedGraphService {

    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final TypeReference<List<ChatCitation>> CITATIONS = new TypeReference<>() { };
    private final AgentWorkflowRunStore workflowRuns;
    private final AgentWorkflowExpressionService expressions;
    private final AgentConditionalGraphStore graphStore;
    private final AgentOrchestrationStore orchestrationStore;
    private final AgentLoopAuditStore auditStore;
    private final AgentToolDispatcher dispatcher;
    private final AgentToolRegistry registry;
    private final AgentCostGovernanceService costGovernance;
    private final AgentCheckpointService checkpoints;
    private final AgentToolResilienceProperties resilience;
    private final AgentOrchestrationProperties orchestration;
    private final ObjectMapper json;

    public AgentWorkflowFixedGraphService(
            AgentWorkflowRunStore workflowRuns,
            AgentWorkflowExpressionService expressions,
            AgentConditionalGraphStore graphStore,
            AgentOrchestrationStore orchestrationStore,
            AgentLoopAuditStore auditStore,
            AgentToolDispatcher dispatcher,
            AgentToolRegistry registry,
            AgentCostGovernanceService costGovernance,
            AgentCheckpointService checkpoints,
            AgentToolResilienceProperties resilience,
            AgentOrchestrationProperties orchestration,
            ObjectMapper json) {
        this.workflowRuns = workflowRuns;
        this.expressions = expressions;
        this.graphStore = graphStore;
        this.orchestrationStore = orchestrationStore;
        this.auditStore = auditStore;
        this.dispatcher = dispatcher;
        this.registry = registry;
        this.costGovernance = costGovernance;
        this.checkpoints = checkpoints;
        this.resilience = resilience;
        this.orchestration = orchestration;
        this.json = json;
    }

    public boolean isWorkflow(UUID runId) {
        return workflowRuns.find(runId).isPresent();
    }

    public AgentLoopService.LoopResult execute(
            Request request, AgentToolDispatcher.ProgressListener listener,
            BooleanSupplier active) {
        AgentWorkflowRunStore.WorkflowRun workflow = workflowRuns.find(request.runId())
                .orElseThrow(() -> new WorkflowExecutionException("WORKFLOW_RUN_NOT_FOUND"));
        AgentWorkflowExpressionService.Graph graph = expressions.validateGraph(workflow.graphSpecJson());
        if (!workflow.toolContractFingerprint().equals(expressions.contractFingerprint(graph))) {
            throw new WorkflowExecutionException("WORKFLOW_TOOL_CONTRACT_CHANGED");
        }
        AgentOrchestrationStore.RunLimits limits = limits();
        AgentCheckpointService.RecoveryState recovery = request.recoveryCheckpointId() == null
                ? null : checkpoints.restoreForTakeover(
                        request.recoveryCheckpointId(), request.runId(),
                        request.workspaceId(), request.userId());
        if (graph.nodes().size() > limits.maxNodes()) {
            throw new WorkflowExecutionException("GRAPH_NODE_LIMIT_EXCEEDED");
        }
        costGovernance.reserve(request.runId(), request.workspaceId(), request.userId(),
                limits.maxModelTokens(), limits.maxEstimatedCostCny());
        AgentRunExecutionBudget budget = budget(
                limits, recovery == null ? null : recovery.budget());
        if (budget.reserveNodes(graph.nodes().size()) < graph.nodes().size()) {
            throw new WorkflowExecutionException("MAX_NODES");
        }
        AgentOrchestrationStore.PlanHandle plan = orchestrationStore.startRun(
                request.runId(), limits, Instant.now());
        listener.onPlanCreated(plan.planId(), plan.version(), limits.maxNodes(), limits.maxParallelism());
        if (recovery != null) checkpoints.linkResume(plan.planId(), recovery.checkpointId());
        persistBudget(request.runId(), budget, listener);

        try {
            ConditionalTaskGraph.Submission submission = ConditionalTaskGraph.parse(
                    new PlannedToolCall("workflow-template", ConditionalTaskGraph.FUNCTION_NAME,
                            workflow.graphSpecJson()), json, registry, request.accessLevel(), limits.maxNodes());
            Map<String, AgentWorkflowExpressionService.NodeDefinition> definitions = graph.nodes().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            AgentWorkflowExpressionService.NodeDefinition::id, item -> item,
                            (left, right) -> left, LinkedHashMap::new));
            Map<String, AgentWorkflowRunStore.WorkflowNode> storedNodes = workflow.nodes().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            AgentWorkflowRunStore.WorkflowNode::logicalNodeId, item -> item,
                            (left, right) -> left, LinkedHashMap::new));
            List<AgentOrchestrationStore.PlanNode> persisted = new ArrayList<>();
            int graphRound = 1;
            for (List<ConditionalTaskGraph.Node> wave : ConditionalTaskGraph.waves(submission.nodes())) {
                List<AgentConditionalGraphStore.GraphNodeDraft> drafts = new ArrayList<>();
                for (int position = 0; position < wave.size(); position++) {
                    ConditionalTaskGraph.Node node = wave.get(position);
                    drafts.add(new AgentConditionalGraphStore.GraphNodeDraft(
                            node.id(), node.logicalId(), position + 1, node.toolName(), node.riskLevel(),
                            node.required(), write(Map.of("arguments", node.argumentsJson())),
                            node.dependencyIds(), node.condition().name(), write(node.expectedErrorCodes())));
                }
                persisted.addAll(graphStore.appendGraph(
                        plan.planId(), request.runId(), graphRound++, 1, drafts, Instant.now()));
            }
            checkpoints.revision(plan.planId(), 1, "WORKFLOW_TEMPLATE_SNAPSHOT", graph.root());
            Map<UUID, AgentOrchestrationStore.PlanNode> planNodes = persisted.stream()
                    .collect(java.util.stream.Collectors.toMap(
                            AgentOrchestrationStore.PlanNode::id, item -> item));
            for (AgentOrchestrationStore.PlanNode node : persisted) {
                listener.onPlanNodeState(node.id(), node.toolName(), node.round(),
                        "PENDING", node.dependencyIds(), null);
            }

            Map<String, Object> inputValues = readMap(workflow.inputJson());
            Map<String, Map<String, Object>> outputs = new LinkedHashMap<>();
            Map<UUID, ConditionalTaskGraph.NodeResult> statuses = new LinkedHashMap<>();
            LinkedHashSet<String> sources = new LinkedHashSet<>();
            List<ChatCitation> citations = new ArrayList<>();
            StringBuilder appendix = new StringBuilder();
            int executed = 0;
            int stepNo = 0;
            int invocation = 0;
            String requiredFailure = null;

            for (List<ConditionalTaskGraph.Node> wave : ConditionalTaskGraph.waves(submission.nodes())) {
                ensureActive(active);
                List<NodeWork> work = new ArrayList<>();
                for (ConditionalTaskGraph.Node spec : wave) {
                    AgentWorkflowRunStore.WorkflowNode stored = storedNodes.get(spec.logicalId());
                    AgentOrchestrationStore.PlanNode planNode = planNodes.get(spec.id());
                    if (stored != null && stored.reusable()) {
                        Map<String, Object> exposed = readMap(stored.exposedOutputJson());
                        outputs.put(spec.logicalId(), exposed);
                        statuses.put(spec.id(), new ConditionalTaskGraph.NodeResult("SUCCEEDED", null));
                        orchestrationStore.updateNode(planNode.id(), "SUCCEEDED", stored.toolCallId(), null, Instant.now());
                        listener.onPlanNodeState(planNode.id(), planNode.toolName(), planNode.round(),
                                "SUCCEEDED", planNode.dependencyIds(), null);
                        appendix.append(stored.promptAppendix() == null ? "" : stored.promptAppendix());
                        sources.addAll(readStrings(stored.sourceUrlsJson()));
                        citations.addAll(readCitations(stored.citationsJson()));
                        continue;
                    }
                    if (!ConditionalTaskGraph.conditionMatches(spec, statuses)) {
                        NodeWork skipped = new NodeWork(spec, definitions.get(spec.logicalId()), planNode,
                                ++stepNo, ++stepNo, ++invocation, Map.of());
                        NodeOutcome outcome = finishWithoutDispatch(
                                request, skipped, "SKIPPED", "BRANCH_CONDITION_NOT_MATCHED", listener);
                        statuses.put(spec.id(), new ConditionalTaskGraph.NodeResult(
                                outcome.status(), outcome.errorCode()));
                        continue;
                    }
                    try {
                        Map<String, Object> resolved = expressions.resolveArguments(
                                definitions.get(spec.logicalId()), inputValues, outputs);
                        work.add(new NodeWork(spec, definitions.get(spec.logicalId()), planNode,
                                ++stepNo, ++stepNo, ++invocation, resolved));
                    }
                    catch (RuntimeException exception) {
                        String code = failureCode(exception);
                        NodeWork failed = new NodeWork(spec, definitions.get(spec.logicalId()), planNode,
                                ++stepNo, ++stepNo, ++invocation, Map.of());
                        NodeOutcome outcome = finishWithoutDispatch(
                                request, failed, "FAILED", code, listener);
                        statuses.put(spec.id(), new ConditionalTaskGraph.NodeResult("FAILED", code));
                        if (spec.required() && requiredFailure == null) requiredFailure = code;
                    }
                }
                List<NodeOutcome> outcomes = executeWave(request, work, budget, listener, active);
                outcomes.sort(Comparator.comparingInt(item -> item.planNode().position()));
                boolean waitingApproval = false;
                boolean cancelled = false;
                for (NodeOutcome outcome : outcomes) {
                    statuses.put(outcome.spec().id(), new ConditionalTaskGraph.NodeResult(
                            outcome.status(), outcome.errorCode()));
                    if (outcome.result() != null) {
                        executed++;
                        outputs.put(outcome.spec().logicalId(), outcome.exposedOutput());
                        appendix.append(outcome.result().systemPromptAppendix());
                        sources.addAll(outcome.result().sourceUrls());
                        citations.addAll(outcome.result().citations());
                    }
                    if (outcome.spec().required() && "FAILED".equals(outcome.status())
                            && requiredFailure == null) requiredFailure = outcome.errorCode();
                    if ("WAITING_APPROVAL".equals(outcome.status())) waitingApproval = true;
                    if ("CANCELLED".equals(outcome.status())) cancelled = true;
                }
                persistBudget(request.runId(), budget, listener);
                checkpoints.checkpoint(
                        plan.planId(), request.runId(), request.workspaceId(), request.userId(),
                        "WORKFLOW_WAVE_COMPLETED", List.of(appendix.toString()), sources, citations,
                        new LinkedHashSet<>(outputs.keySet()), budget.snapshot());
                if (cancelled) throw new WorkflowExecutionException("CANCELLED");
                if (waitingApproval) {
                    AgentOrchestrationStore.BudgetSnapshot closed =
                            closeBudget(request.runId(), budget, listener);
                    orchestrationStore.finishPlan(plan.planId(), "COMPLETED", Instant.now());
                    return new AgentLoopService.LoopResult(
                            appendix.toString(), List.copyOf(sources), List.copyOf(citations),
                            ModelUsage.unknown(), executed, false, plan.planId(), closed);
                }
            }
            if (requiredFailure != null) throw new WorkflowExecutionException(requiredFailure);
            AgentOrchestrationStore.BudgetSnapshot closed = closeBudget(request.runId(), budget, listener);
            orchestrationStore.finishPlan(plan.planId(), "COMPLETED", Instant.now());
            return new AgentLoopService.LoopResult(
                    appendix.toString(), List.copyOf(sources), List.copyOf(citations),
                    ModelUsage.unknown(), executed, false, plan.planId(), closed);
        }
        catch (RuntimeException exception) {
            closeBudget(request.runId(), budget, listener);
            orchestrationStore.finishPlan(plan.planId(), "FAILED", Instant.now());
            throw exception;
        }
    }

    private List<NodeOutcome> executeWave(
            Request request, List<NodeWork> work, AgentRunExecutionBudget budget,
            AgentToolDispatcher.ProgressListener listener, BooleanSupplier active) {
        if (work.isEmpty()) return List.of();
        int parallelism = Math.max(1, Math.min(orchestration.getMaxParallelism(), work.size()));
        try (ExecutorService executor = Executors.newFixedThreadPool(
                parallelism, Thread.ofVirtual().name("workflow-node-", 0).factory())) {
            List<Future<NodeOutcome>> futures = work.stream()
                    .map(item -> executor.submit(() -> executeNode(request, item, budget, listener, active)))
                    .toList();
            List<NodeOutcome> result = new ArrayList<>();
            for (Future<NodeOutcome> future : futures) {
                try { result.add(future.get()); }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new WorkflowExecutionException("CANCELLED", exception);
                }
                catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    if (cause instanceof RuntimeException runtime) throw runtime;
                    throw new WorkflowExecutionException("WORKFLOW_NODE_EXECUTION_FAILED", exception);
                }
            }
            return List.copyOf(result);
        }
    }

    private NodeOutcome executeNode(
            Request request, NodeWork work, AgentRunExecutionBudget budget,
            AgentToolDispatcher.ProgressListener listener, BooleanSupplier active) {
        ensureActive(active);
        Instant now = Instant.now();
        AgentWorkflowRunStore.NodeAttempt attempt = workflowRuns.beginNode(
                request.runId(), work.spec().logicalId(), request.leaseToken(),
                request.runAttempt(), request.workerId(), write(work.arguments()), now);
        updatePlan(work, "RUNNING", null, null, listener);
        try {
            AgentToolDispatcher.ExecutionResult result = dispatcher.execute(
                    new AgentToolDispatcher.ExecutionContext(
                            request.runId(), request.workspaceId(), request.userId(),
                            request.systemAdmin(), request.accessLevel(), work.toolStep(),
                            work.planNode().round(), work.invocation(), budget),
                    work.spec().toolName(), work.arguments(), listener, active);
            ensureActive(active);
            String status = "human-approval".equals(result.resultModel())
                    ? "WAITING_APPROVAL" : "SUCCEEDED";
            Map<String, Object> exposed = status.equals("SUCCEEDED")
                    ? expressions.expose(work.definition(), result.observation()) : Map.of();
            workflowRuns.finishNode(
                    request.runId(), work.spec().logicalId(), request.leaseToken(), attempt.id(),
                    status, result.toolCallId(), work.planNode().id(), write(result.observation()),
                    write(exposed), result.systemPromptAppendix(), write(result.sourceUrls()),
                    write(result.citations()), 0, 0, BigDecimal.ZERO, null, Instant.now());
            updatePlan(work, status, result.toolCallId(), null, listener);
            audit(request.runId(), work, status, write(result.observation()), null);
            return new NodeOutcome(work.spec(), work.planNode(), status, null, result, exposed);
        }
        catch (AgentToolDispatcher.DispatchException exception) {
            String status = "TOOL_CANCELLED".equals(exception.errorCode()) ? "CANCELLED" : "FAILED";
            workflowRuns.finishNode(
                    request.runId(), work.spec().logicalId(), request.leaseToken(), attempt.id(),
                    status, null, work.planNode().id(), "{}", "{}", "", "[]", "[]",
                    0, 0, BigDecimal.ZERO, exception.errorCode(), Instant.now());
            updatePlan(work, status, null, exception.errorCode(), listener);
            audit(request.runId(), work, status, "{}", exception.errorCode());
            return new NodeOutcome(work.spec(), work.planNode(), status,
                    exception.errorCode(), null, Map.of());
        }
    }

    private NodeOutcome finishWithoutDispatch(
            Request request, NodeWork work, String status, String errorCode,
            AgentToolDispatcher.ProgressListener listener) {
        AgentWorkflowRunStore.NodeAttempt attempt = workflowRuns.beginNode(
                request.runId(), work.spec().logicalId(), request.leaseToken(), request.runAttempt(),
                request.workerId(), write(work.arguments()), Instant.now());
        workflowRuns.finishNode(
                request.runId(), work.spec().logicalId(), request.leaseToken(), attempt.id(),
                status, null, work.planNode().id(), "{}", "{}", "", "[]", "[]",
                0, 0, BigDecimal.ZERO, errorCode, Instant.now());
        updatePlan(work, status, null, errorCode, listener);
        audit(request.runId(), work, status, "{}", errorCode);
        return new NodeOutcome(work.spec(), work.planNode(), status, errorCode, null, Map.of());
    }

    private void updatePlan(
            NodeWork work, String status, UUID toolCallId, String errorCode,
            AgentToolDispatcher.ProgressListener listener) {
        orchestrationStore.updateNode(work.planNode().id(), status, toolCallId, errorCode, Instant.now());
        listener.onPlanNodeState(work.planNode().id(), work.planNode().toolName(), work.planNode().round(),
                status, work.planNode().dependencyIds(), errorCode);
    }

    private void audit(UUID runId, NodeWork work, String status, String output, String errorCode) {
        Instant now = Instant.now();
        auditStore.recordStep(runId, UUID.randomUUID(), work.observationStep(),
                "OBSERVATION", "SUCCEEDED".equals(status) ? "SUCCEEDED" : "FAILED",
                write(Map.of("round", work.planNode().round(), "logicalNodeId", work.spec().logicalId(),
                        "toolName", work.spec().toolName())),
                write(Map.of("output", readObject(output), "status", status,
                        "errorCode", errorCode == null ? "" : errorCode)), now, now);
    }

    private AgentOrchestrationStore.RunLimits limits() {
        return new AgentOrchestrationStore.RunLimits(
                Math.max(1, orchestration.getMaxNodes()),
                Math.max(1, orchestration.getMaxParallelism()),
                Math.max(1, resilience.getMaxTotalAttempts()),
                Math.max(1, orchestration.getMaxModelTokens()),
                orchestration.getMaxEstimatedCostCny().max(new BigDecimal("0.000001")));
    }

    private AgentRunExecutionBudget budget(
            AgentOrchestrationStore.RunLimits limits,
            AgentOrchestrationStore.BudgetSnapshot restored) {
        return new AgentRunExecutionBudget(
                Duration.ofSeconds(Math.max(1, resilience.getRunTimeoutSeconds())),
                limits.maxToolAttempts(),
                Duration.ofSeconds(Math.max(1, resilience.getMaxToolDurationSeconds())),
                limits.maxNodes(), limits.maxModelTokens(), limits.maxEstimatedCostCny(), restored);
    }

    private void persistBudget(
            UUID runId, AgentRunExecutionBudget budget,
            AgentToolDispatcher.ProgressListener listener) {
        AgentOrchestrationStore.BudgetSnapshot value = budget.snapshot();
        orchestrationStore.updateBudget(runId, value, Instant.now());
        if (value.exhaustionReason() == null) listener.onBudgetUpdated(value);
        else listener.onBudgetExhausted(value);
    }

    private AgentOrchestrationStore.BudgetSnapshot closeBudget(
            UUID runId, AgentRunExecutionBudget budget,
            AgentToolDispatcher.ProgressListener listener) {
        AgentOrchestrationStore.BudgetSnapshot current = budget.snapshot();
        AgentOrchestrationStore.BudgetSnapshot closed = new AgentOrchestrationStore.BudgetSnapshot(
                current.usedNodes(), current.usedToolAttempts(), current.usedModelTokens(),
                current.estimatedCostCny(), current.exhaustionReason() == null ? "CLOSED" : "EXHAUSTED",
                current.exhaustionReason());
        orchestrationStore.updateBudget(runId, closed, Instant.now());
        if (closed.exhaustionReason() == null) listener.onBudgetUpdated(closed);
        else listener.onBudgetExhausted(closed);
        return closed;
    }

    private Map<String, Object> readMap(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try { return json.readValue(value, JSON_MAP); }
        catch (JsonProcessingException exception) {
            throw new WorkflowExecutionException("WORKFLOW_STATE_INVALID", exception);
        }
    }

    private List<String> readStrings(String value) {
        if (value == null || value.isBlank()) return List.of();
        try { return json.readValue(value, STRING_LIST); }
        catch (JsonProcessingException exception) { return List.of(); }
    }

    private List<ChatCitation> readCitations(String value) {
        if (value == null || value.isBlank()) return List.of();
        try { return json.readValue(value, CITATIONS); }
        catch (JsonProcessingException exception) { return List.of(); }
    }

    private Object readObject(String value) {
        try { return json.readTree(value); }
        catch (JsonProcessingException exception) { return Map.of(); }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) {
            throw new WorkflowExecutionException("WORKFLOW_SERIALIZATION_FAILED", exception);
        }
    }

    private static void ensureActive(BooleanSupplier active) {
        if (active != null && !active.getAsBoolean()) {
            throw new WorkflowExecutionException("CANCELLED");
        }
    }

    private static String failureCode(RuntimeException exception) {
        String message = exception.getMessage();
        if (message != null && message.matches("[A-Z0-9_:-]{3,160}")) {
            return message.length() <= 64 ? message : message.substring(0, 64);
        }
        return "WORKFLOW_INPUT_RESOLUTION_FAILED";
    }

    public record Request(
            UUID runId, UUID workspaceId, UUID userId, boolean systemAdmin,
            AgentToolDefinition.AccessLevel accessLevel, UUID leaseToken,
            int runAttempt, String workerId, UUID recoveryCheckpointId) { }

    private record NodeWork(
            ConditionalTaskGraph.Node spec,
            AgentWorkflowExpressionService.NodeDefinition definition,
            AgentOrchestrationStore.PlanNode planNode,
            int toolStep, int observationStep, int invocation,
            Map<String, Object> arguments) { }

    private record NodeOutcome(
            ConditionalTaskGraph.Node spec,
            AgentOrchestrationStore.PlanNode planNode,
            String status, String errorCode,
            AgentToolDispatcher.ExecutionResult result,
            Map<String, Object> exposedOutput) { }

    public static final class WorkflowExecutionException extends RuntimeException {
        public WorkflowExecutionException(String message) { super(message); }
        public WorkflowExecutionException(String message, Throwable cause) { super(message, cause); }
    }
}
