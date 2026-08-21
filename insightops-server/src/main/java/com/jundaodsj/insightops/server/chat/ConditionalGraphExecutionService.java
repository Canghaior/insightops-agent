package com.jundaodsj.insightops.server.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.agent.application.AgentConditionalGraphStore;
import com.jundaodsj.insightops.agent.application.AgentLoopAuditStore;
import com.jundaodsj.insightops.agent.application.AgentOrchestrationStore;
import com.jundaodsj.insightops.model.application.AgentPlanningModelGateway.PlannedToolCall;
import com.jundaodsj.insightops.server.tool.AgentRunExecutionBudget;
import com.jundaodsj.insightops.tool.application.registry.AgentToolDefinition;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

@Service
public class ConditionalGraphExecutionService {

    private final AgentConditionalGraphStore graphStore;
    private final AgentOrchestrationStore orchestrationStore;
    private final AgentLoopAuditStore auditStore;
    private final AgentToolDispatcher dispatcher;
    private final AgentOrchestrationMetrics metrics;
    private final ObjectMapper objectMapper;

    public ConditionalGraphExecutionService(
            AgentConditionalGraphStore graphStore,
            AgentOrchestrationStore orchestrationStore,
            AgentLoopAuditStore auditStore,
            AgentToolDispatcher dispatcher,
            AgentOrchestrationMetrics metrics,
            ObjectMapper objectMapper) {
        this.graphStore = graphStore;
        this.orchestrationStore = orchestrationStore;
        this.auditStore = auditStore;
        this.dispatcher = dispatcher;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    public Result execute(
            Request request,
            ConditionalTaskGraph.Submission submission,
            AgentRunExecutionBudget budget,
            AgentToolDispatcher.ProgressListener listener,
            BooleanSupplier active,
            Set<String> executedSignatures) {
        List<ConditionalTaskGraph.Node> graphNodes = submission.nodes();
        List<AgentConditionalGraphStore.GraphNodeDraft> drafts = new ArrayList<>();
        for (int index = 0; index < graphNodes.size(); index++) {
            ConditionalTaskGraph.Node node = graphNodes.get(index);
            drafts.add(new AgentConditionalGraphStore.GraphNodeDraft(
                    node.id(), node.logicalId(), index + 1, node.toolName(), node.riskLevel(),
                    node.required(), json(Map.of("arguments", parseOrText(node.argumentsJson()))),
                    node.dependencyIds(), node.condition().name(), json(node.expectedErrorCodes())));
        }
        List<AgentOrchestrationStore.PlanNode> stored = graphStore.appendGraph(
                request.planId(), request.runId(), request.round(), request.revision(), drafts, Instant.now());
        Map<UUID, AgentOrchestrationStore.PlanNode> persisted = new LinkedHashMap<>();
        stored.forEach(node -> {
            persisted.put(node.id(), node);
            listener.onPlanNodeState(node.id(), node.toolName(), node.round(),
                    "PENDING", node.dependencyIds(), null);
        });

        Map<UUID, ConditionalTaskGraph.NodeResult> statuses = new LinkedHashMap<>();
        List<NodeOutcome> outcomes = new ArrayList<>();
        int stepNo = request.startStepNo();
        int invocation = 0;
        boolean waitingApproval = false;
        boolean cancelled = false;

        for (List<ConditionalTaskGraph.Node> wave : ConditionalTaskGraph.waves(graphNodes)) {
            ensureActive(active);
            List<NodeWork> executable = new ArrayList<>();
            for (ConditionalTaskGraph.Node spec : wave) {
                AgentOrchestrationStore.PlanNode node = persisted.get(spec.id());
                PlannedToolCall call = new PlannedToolCall(
                        spec.logicalId(), spec.toolName(), spec.argumentsJson());
                NodeWork work = new NodeWork(node, spec, call, ++stepNo, ++stepNo, ++invocation);
                if (!ConditionalTaskGraph.conditionMatches(spec, statuses)) {
                    NodeOutcome skipped = skipped(work, "BRANCH_CONDITION_NOT_MATCHED", listener);
                    outcomes.add(skipped);
                    statuses.put(spec.id(), new ConditionalTaskGraph.NodeResult(
                            skipped.status(), skipped.errorCode()));
                    continue;
                }
                String signature = spec.toolName() + "\n" + spec.argumentsJson();
                if (!executedSignatures.add(signature)) {
                    NodeOutcome skipped = skipped(work, "DUPLICATE_TOOL_CALL", listener);
                    outcomes.add(skipped);
                    statuses.put(spec.id(), new ConditionalTaskGraph.NodeResult(
                            skipped.status(), skipped.errorCode()));
                    continue;
                }
                try { executable.add(work.withArguments(arguments(spec.argumentsJson()))); }
                catch (GraphExecutionException exception) {
                    NodeOutcome failed = failed(work, exception.errorCode(), listener);
                    outcomes.add(failed);
                    statuses.put(spec.id(), new ConditionalTaskGraph.NodeResult(
                            failed.status(), failed.errorCode()));
                }
            }
            Instant startedAt = Instant.now();
            List<NodeOutcome> waveOutcomes = executeWave(
                    request, executable, budget, listener, active);
            metrics.layer(executable.size(), Duration.between(startedAt, Instant.now()));
            waveOutcomes.sort(Comparator.comparingInt(outcome -> outcome.node().position()));
            for (NodeOutcome outcome : waveOutcomes) {
                outcomes.add(outcome);
                statuses.put(outcome.node().id(), new ConditionalTaskGraph.NodeResult(
                        outcome.status(), outcome.errorCode()));
                waitingApproval |= "WAITING_APPROVAL".equals(outcome.status());
                cancelled |= "CANCELLED".equals(outcome.status());
            }
            if (waitingApproval || cancelled) break;
        }
        outcomes.forEach(outcome -> auditObservation(request.runId(), request.round(), outcome));
        return new Result(stepNo, List.copyOf(outcomes), aggregate(outcomes),
                waitingApproval, cancelled, Map.copyOf(statuses));
    }

    private List<NodeOutcome> executeWave(
            Request request, List<NodeWork> work, AgentRunExecutionBudget budget,
            AgentToolDispatcher.ProgressListener listener, BooleanSupplier active) {
        if (work.isEmpty()) return List.of();
        int parallelism = Math.max(1, Math.min(request.maxParallelism(), work.size()));
        List<Future<NodeOutcome>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(
                parallelism, Thread.ofVirtual().name("agent-graph-", 0).factory())) {
            for (NodeWork item : work) {
                futures.add(executor.submit(() -> executeNode(request, item, budget, listener, active)));
            }
            List<NodeOutcome> outcomes = new ArrayList<>();
            for (Future<NodeOutcome> future : futures) {
                try { outcomes.add(future.get()); }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new GraphExecutionException("CANCELLED", exception);
                }
                catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    if (cause instanceof RuntimeException runtime) throw runtime;
                    throw new GraphExecutionException("GRAPH_EXECUTION_FAILED", exception);
                }
            }
            return outcomes;
        }
    }

    private NodeOutcome executeNode(
            Request request, NodeWork item, AgentRunExecutionBudget budget,
            AgentToolDispatcher.ProgressListener listener, BooleanSupplier active) {
        ensureActive(active);
        updateNode(item.node(), "RUNNING", null, null, listener);
        try {
            AgentToolDispatcher.ExecutionResult result = dispatcher.execute(
                    new AgentToolDispatcher.ExecutionContext(
                            request.runId(), request.workspaceId(), request.userId(),
                            request.systemAdmin(), request.accessLevel(), item.toolStep(),
                            request.round(), item.invocation(), budget),
                    item.spec().toolName(), item.arguments(), listener, active);
            ensureActive(active);
            String status = "human-approval".equals(result.resultModel())
                    ? "WAITING_APPROVAL" : "SUCCEEDED";
            updateNode(item.node(), status, result.toolCallId(), null, listener);
            return new NodeOutcome(item.node(), item.spec(), item.call(), status, null,
                    result, json(result.observation()), item.observationStep());
        }
        catch (AgentToolDispatcher.DispatchException exception) {
            String status = "TOOL_CANCELLED".equals(exception.errorCode()) ? "CANCELLED" : "FAILED";
            if ("TOOL_RUN_BUDGET_EXHAUSTED".equals(exception.errorCode())) budget.exhaust("MAX_TOOL_ATTEMPTS");
            updateNode(item.node(), status, null, exception.errorCode(), listener);
            return new NodeOutcome(item.node(), item.spec(), item.call(), status,
                    exception.errorCode(), null, error(exception.errorCode()), item.observationStep());
        }
        catch (GraphExecutionException exception) {
            updateNode(item.node(), "CANCELLED", null, exception.errorCode(), listener);
            return new NodeOutcome(item.node(), item.spec(), item.call(), "CANCELLED",
                    exception.errorCode(), null, error(exception.errorCode()), item.observationStep());
        }
        catch (RuntimeException exception) {
            updateNode(item.node(), "FAILED", null, "TOOL_INTERNAL_ERROR", listener);
            return new NodeOutcome(item.node(), item.spec(), item.call(), "FAILED",
                    "TOOL_INTERNAL_ERROR", null, error("TOOL_INTERNAL_ERROR"), item.observationStep());
        }
    }

    private NodeOutcome skipped(NodeWork work, String errorCode,
                                AgentToolDispatcher.ProgressListener listener) {
        updateNode(work.node(), "SKIPPED", null, errorCode, listener);
        return new NodeOutcome(work.node(), work.spec(), work.call(), "SKIPPED",
                errorCode, null, error(errorCode), work.observationStep());
    }

    private NodeOutcome failed(NodeWork work, String errorCode,
                               AgentToolDispatcher.ProgressListener listener) {
        updateNode(work.node(), "FAILED", null, errorCode, listener);
        return new NodeOutcome(work.node(), work.spec(), work.call(), "FAILED",
                errorCode, null, error(errorCode), work.observationStep());
    }

    private void updateNode(AgentOrchestrationStore.PlanNode node, String status,
                            UUID toolCallId, String errorCode,
                            AgentToolDispatcher.ProgressListener listener) {
        orchestrationStore.updateNode(node.id(), status, toolCallId, errorCode, Instant.now());
        metrics.node(status.toLowerCase());
        listener.onPlanNodeState(node.id(), node.toolName(), node.round(), status,
                node.dependencyIds(), errorCode);
    }

    private void auditObservation(UUID runId, int round, NodeOutcome outcome) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("observation", parseOrText(outcome.observation()));
        output.put("logicalNodeId", outcome.spec().logicalId());
        output.put("condition", outcome.spec().condition().name());
        output.put("required", outcome.spec().required());
        if (outcome.errorCode() != null) output.put("errorCode", outcome.errorCode());
        Instant now = Instant.now();
        auditStore.recordStep(runId, UUID.randomUUID(), outcome.observationStep(),
                "OBSERVATION", "SUCCEEDED".equals(outcome.status()) ? "SUCCEEDED" : "FAILED",
                json(Map.of("round", round, "providerToolCallId", outcome.call().id(),
                        "toolName", outcome.call().name())), json(output), now, now);
    }

    private String aggregate(List<NodeOutcome> outcomes) {
        List<Map<String, Object>> values = outcomes.stream().map(outcome -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("nodeId", outcome.spec().logicalId());
            value.put("toolName", outcome.spec().toolName());
            value.put("status", outcome.status());
            value.put("required", outcome.spec().required());
            if (outcome.errorCode() != null) value.put("errorCode", outcome.errorCode());
            value.put("observation", parseOrText(limited(outcome.observation(), 4_000)));
            return value;
        }).toList();
        return json(Map.of("status", "completed", "nodes", values));
    }

    private Map<String, Object> arguments(String json) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(
                    json, new TypeReference<Map<String, Object>>() { });
            return parsed == null ? Map.of() : Map.copyOf(parsed);
        }
        catch (JsonProcessingException exception) {
            throw new GraphExecutionException("GRAPH_TOOL_ARGUMENTS_INVALID", exception);
        }
    }

    private Object parseOrText(String value) {
        try { return objectMapper.readTree(value); }
        catch (JsonProcessingException exception) { return limited(value, 12_000); }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) {
            throw new GraphExecutionException("GRAPH_AUDIT_SERIALIZATION_FAILED", exception);
        }
    }

    private static String error(String code) {
        return "{\"status\":\"error\",\"errorCode\":\"" + code + "\"}";
    }

    private static String limited(String value, int limit) {
        if (value == null) return "";
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private static void ensureActive(BooleanSupplier active) {
        if (active != null && !active.getAsBoolean()) throw new GraphExecutionException("CANCELLED");
    }

    public record Request(
            UUID runId, UUID planId, UUID workspaceId, UUID userId,
            boolean systemAdmin, AgentToolDefinition.AccessLevel accessLevel,
            int round, int revision, int startStepNo, int maxParallelism) { }

    public record Result(
            int lastStepNo, List<NodeOutcome> outcomes, String aggregateObservation,
            boolean waitingApproval, boolean cancelled,
            Map<UUID, ConditionalTaskGraph.NodeResult> statuses) {
        public int executedNodes() {
            return (int) outcomes.stream().filter(outcome -> outcome.result() != null).count();
        }
        public List<AgentToolDispatcher.ExecutionResult> successfulResults() {
            return outcomes.stream().map(NodeOutcome::result)
                    .filter(java.util.Objects::nonNull).toList();
        }
    }

    public record NodeOutcome(
            AgentOrchestrationStore.PlanNode node,
            ConditionalTaskGraph.Node spec,
            PlannedToolCall call,
            String status,
            String errorCode,
            AgentToolDispatcher.ExecutionResult result,
            String observation,
            int observationStep) { }

    private record NodeWork(
            AgentOrchestrationStore.PlanNode node,
            ConditionalTaskGraph.Node spec,
            PlannedToolCall call,
            int toolStep,
            int observationStep,
            int invocation,
            Map<String, Object> arguments) {
        NodeWork(AgentOrchestrationStore.PlanNode node, ConditionalTaskGraph.Node spec,
                 PlannedToolCall call, int toolStep, int observationStep, int invocation) {
            this(node, spec, call, toolStep, observationStep, invocation, null);
        }
        NodeWork withArguments(Map<String, Object> value) {
            return new NodeWork(node, spec, call, toolStep, observationStep, invocation, value);
        }
    }

    public static final class GraphExecutionException extends RuntimeException {
        private final String errorCode;
        public GraphExecutionException(String errorCode) { super(errorCode); this.errorCode = errorCode; }
        public GraphExecutionException(String errorCode, Throwable cause) {
            super(errorCode, cause); this.errorCode = errorCode;
        }
        public String errorCode() { return errorCode; }
    }
}
