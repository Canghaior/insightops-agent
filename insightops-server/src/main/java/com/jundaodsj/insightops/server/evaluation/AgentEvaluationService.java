package com.jundaodsj.insightops.server.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.agent.application.AgentEvaluationStore;
import com.jundaodsj.insightops.agent.application.AgentEvaluationStore.CaseDraft;
import com.jundaodsj.insightops.agent.application.AgentEvaluationStore.CaseResult;
import com.jundaodsj.insightops.agent.application.AgentEvaluationStore.CaseResultDraft;
import com.jundaodsj.insightops.agent.application.AgentEvaluationStore.Candidate;
import com.jundaodsj.insightops.agent.application.AgentEvaluationStore.Dataset;
import com.jundaodsj.insightops.agent.application.AgentEvaluationStore.EvaluationCase;
import com.jundaodsj.insightops.agent.application.AgentEvaluationStore.EvaluationLease;
import com.jundaodsj.insightops.agent.application.AgentEvaluationStore.EvaluationRun;
import com.jundaodsj.insightops.agent.application.AgentEvaluationStore.RuntimeProfile;
import com.jundaodsj.insightops.agent.application.AgentEvaluationStore.Summary;
import com.jundaodsj.insightops.agent.application.AgentRunQuery;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.infrastructure.config.DeepSeekModelProperties;
import com.jundaodsj.insightops.model.application.ModelUsage;
import com.jundaodsj.insightops.server.chat.AgentLoopService;
import com.jundaodsj.insightops.server.chat.AgentToolDispatcher;
import com.jundaodsj.insightops.tool.application.registry.AgentToolDefinition;
import com.jundaodsj.insightops.tool.application.registry.AgentToolRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

@Service
public class AgentEvaluationService {

    private static final Pattern KEY = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{1,95}");
    private static final Pattern DOMAIN = Pattern.compile("[a-zA-Z0-9.-]{3,253}");
    private static final BigDecimal ZERO_COST = new BigDecimal("0.000000");

    private final AgentEvaluationStore store;
    private final AgentRunQuery runQuery;
    private final AgentToolRegistry registry;
    private final ObjectMapper json;
    private final ObjectProvider<AgentLoopService> loopProvider;
    private final DeepSeekModelProperties modelProperties;
    private final ScheduledExecutorService heartbeatExecutor;
    private final AgentEvaluationQueueProperties queueProperties;
    private final AgentEvaluationMetrics metrics;

    public AgentEvaluationService(
            AgentEvaluationStore store,
            AgentRunQuery runQuery,
            AgentToolRegistry registry,
            ObjectMapper json,
            ObjectProvider<AgentLoopService> loopProvider,
            DeepSeekModelProperties modelProperties,
            @Qualifier("agentEvaluationHeartbeatExecutor") ScheduledExecutorService heartbeatExecutor,
            AgentEvaluationQueueProperties queueProperties,
            AgentEvaluationMetrics metrics) {
        this.store = store;
        this.runQuery = runQuery;
        this.registry = registry;
        this.json = json;
        this.loopProvider = loopProvider;
        this.modelProperties = modelProperties;
        this.heartbeatExecutor = heartbeatExecutor;
        this.queueProperties = queueProperties;
        this.metrics = metrics;
    }

    public AgentEvaluationStore.Overview overview(UUID workspaceId) {
        return store.overview(workspaceId, 20);
    }

    public Defaults defaults() {
        return new Defaults(modelProperties.model(), 0.0,
                Math.max(256, Math.min(1_024, modelProperties.maxOutputTokens())),
                toolContractHash());
    }

    public Dataset createDataset(
            UUID workspaceId, UUID userId, AgentEvaluationStore.DatasetDraft draft) {
        AgentEvaluationStore.DatasetDraft normalized = validateDataset(draft);
        return store.createDataset(workspaceId, userId, normalized, Instant.now());
    }

    public Dataset deriveFromRun(
            ActorContext actor, UUID userId, UUID baseDatasetId, UUID sourceRunId,
            CaseDraft draftWithoutQuestion) {
        AgentRunQuery.RunDetail run = runQuery.findRun(actor, sourceRunId)
                .orElseThrow(() -> new IllegalArgumentException("Source Agent run not found"));
        CaseDraft draft = new CaseDraft(
                draftWithoutQuestion.caseKey(), run.question(), draftWithoutQuestion.expectedTools(),
                draftWithoutQuestion.forbiddenTools(), draftWithoutQuestion.requiredSourceDomains(),
                draftWithoutQuestion.expectRecovery(), draftWithoutQuestion.maxToolRounds(),
                draftWithoutQuestion.maxDurationMs(), draftWithoutQuestion.maxTokens(),
                draftWithoutQuestion.maxCostCny(), draftWithoutQuestion.required(), sourceRunId);
        return store.deriveDataset(actor.workspaceId(), userId, baseDatasetId,
                validateCase(draft), Instant.now());
    }

    public Candidate createCandidate(
            UUID workspaceId, UUID userId, AgentEvaluationStore.CandidateDraft draft) {
        String name = text(draft.name(), "Candidate name", 128);
        String appendix = optionalText(draft.plannerPromptAppendix(), 8_000);
        String model = text(draft.modelName(), "Model name", 128);
        if (draft.temperature() < 0 || draft.temperature() > 2) {
            throw new IllegalArgumentException("Temperature must be between 0 and 2");
        }
        if (draft.maxOutputTokens() < 1 || draft.maxOutputTokens() > 8192) {
            throw new IllegalArgumentException("maxOutputTokens must be between 1 and 8192");
        }
        if (draft.basedOnId() != null
                && store.findCandidate(workspaceId, draft.basedOnId()).isEmpty()) {
            throw new IllegalArgumentException("Base release candidate not found");
        }
        return store.createCandidate(workspaceId, userId,
                new AgentEvaluationStore.CandidateDraft(
                        name, appendix, model, draft.temperature(), draft.maxOutputTokens(),
                        toolContractHash(), draft.basedOnId()), Instant.now());
    }

    public EvaluationRun startEvaluation(
            UUID workspaceId, UUID userId, UUID datasetId, UUID candidateId) {
        Candidate candidate = store.findCandidate(workspaceId, candidateId)
                .orElseThrow(() -> new IllegalArgumentException("Release candidate not found"));
        if (!candidate.toolContractHash().equals(toolContractHash())) {
            throw new IllegalStateException("Tool contract changed; create a new candidate");
        }
        EvaluationRun run = store.queueEvaluation(
                workspaceId, userId, datasetId, candidateId, Instant.now());
        return run;
    }

    public EvaluationRun detail(UUID workspaceId, UUID runId) {
        return store.findEvaluation(workspaceId, runId)
                .orElseThrow(() -> new IllegalArgumentException("Agent evaluation run not found"));
    }

    public Candidate activate(
            UUID workspaceId, UUID userId, UUID candidateId, String reason) {
        return store.activateCandidate(workspaceId, userId, candidateId,
                toolContractHash(), optionalText(reason, 500), Instant.now());
    }

    String toolContractHash() {
        try {
            List<Object> contracts = registry.definitions().stream()
                    .sorted(Comparator.comparing(AgentToolDefinition::name)).map(definition -> {
                LinkedHashMap<String, Object> item = new LinkedHashMap<>();
                item.put("name", definition.name());
                item.put("version", definition.version());
                item.put("enabled", definition.enabled());
                item.put("access", definition.accessLevel().name());
                item.put("risk", definition.riskLevel().name());
                item.put("approval", definition.approvalPolicy().name());
                item.put("input", definition.inputSchema());
                item.put("output", definition.outputSchema());
                return item;
            }).map(Object.class::cast).toList();
            byte[] encoded = json.writeValueAsString(contracts).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
        }
        catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to fingerprint Agent tool contracts", exception);
        }
    }

    void executeClaim(EvaluationLease lease) {
        Instant evaluationStarted = Instant.now();
        metrics.started();
        boolean passed = false;
        boolean terminal = false;
        try (LeaseGuard guard = new LeaseGuard(lease)) {
            AgentLoopService loop = loopProvider.getIfAvailable();
            if (loop == null) throw new EvaluationUnavailableException("AGENT_MODEL_UNAVAILABLE");
            guard.renewNow();
            for (UUID orphanedRunId : store.prepareEvaluationAttempt(
                    lease.evaluationRunId(), lease.leaseToken(), Instant.now())) {
                try {
                    loop.releaseCost(orphanedRunId, "EVALUATION_WORKER_LOST");
                }
                catch (RuntimeException ignored) {
                    // Cost reconciliation is idempotent and will be retried by operations if needed.
                }
            }
            EvaluationRun queued = store.findEvaluation(
                    lease.workspaceId(), lease.evaluationRunId()).orElseThrow();
            Dataset dataset = store.findDataset(lease.workspaceId(), queued.datasetId()).orElseThrow();
            Candidate candidate = store.findCandidate(
                    lease.workspaceId(), queued.candidateId()).orElseThrow();
            if (lease.requestedBy() == null) {
                throw new EvaluationUnavailableException("EVALUATION_REQUESTER_MISSING");
            }
            RuntimeProfile profile = new RuntimeProfile(
                    candidate.id(), candidate.version(), candidate.name(),
                    candidate.plannerPromptAppendix(), candidate.modelName(), candidate.temperature(),
                    candidate.maxOutputTokens(), candidate.toolContractHash(), candidate.createdAt());
            Map<UUID, CaseResultDraft> completed = new HashMap<>();
            for (CaseResult result : queued.results()) {
                completed.put(result.caseId(), persisted(result));
            }
            List<CaseOutcome> outcomes = new ArrayList<>();
            for (EvaluationCase item : dataset.cases()) {
                CaseResultDraft result = completed.get(item.id());
                if (result == null) {
                    result = executeCase(loop, lease, guard, profile, item);
                }
                outcomes.add(new CaseOutcome(item, result));
            }
            guard.renewNow();
            Summary summary = summarize(dataset, outcomes);
            terminal = store.completeEvaluation(
                    lease.evaluationRunId(), lease.leaseToken(), summary, Instant.now());
            if (!terminal) throw new EvaluationLeaseLostException();
            passed = summary.passed();
        }
        catch (EvaluationLeaseLostException exception) {
            metrics.leaseLost();
        }
        catch (RuntimeException exception) {
            terminal = store.failEvaluation(
                    lease.evaluationRunId(), lease.leaseToken(), failureCode(exception), Instant.now());
            if (!terminal) metrics.leaseLost();
        }
        finally {
            metrics.finished(passed, terminal, Duration.between(evaluationStarted, Instant.now()));
        }
    }

    private CaseResultDraft executeCase(
            AgentLoopService loop, EvaluationLease lease, LeaseGuard guard,
            RuntimeProfile profile, EvaluationCase item) {
        UUID runId = UUID.randomUUID();
        Instant started = Instant.now();
        String traceId = "agent-eval-" + lease.evaluationRunId().toString().substring(0, 8)
                + "-" + runId.toString().substring(0, 8);
        guard.renewNow();
        if (!store.startAgentRun(
                new ActorContext(lease.requestedBy(), lease.workspaceId()),
                lease.evaluationRunId(), item.id(), lease.leaseToken(), runId, traceId,
                item.question(), started)) {
            throw new EvaluationLeaseLostException();
        }
        try {
            AgentLoopService.LoopResult loopResult = loop.run(
                    new AgentLoopService.LoopRequest(
                            runId, lease.workspaceId(), lease.requestedBy(), true,
                            AgentToolDefinition.AccessLevel.SYSTEM_ADMIN, item.question(),
                            null, profile, true), NOOP_LISTENER, guard::isActive);
            ModelUsage usage = loopResult.planningUsage();
            int inputTokens = positive(usage.inputTokens());
            int outputTokens = positive(usage.outputTokens());
            long totalTokens = totalTokens(usage);
            BigDecimal cost = loopResult.budget() == null
                    || loopResult.budget().estimatedCostCny() == null
                    ? ZERO_COST : loopResult.budget().estimatedCostCny();
            guard.renewNow();
            loop.settleCost(runId, usage);
            if (!store.completeAgentRun(
                    lease.evaluationRunId(), lease.leaseToken(), runId,
                    profile.modelName(), inputTokens, outputTokens,
                    cost, loopResult.sourceUrls(), Instant.now())) {
                throw new EvaluationLeaseLostException();
            }
            AgentEvaluationStore.RunFacts facts = store.inspectAgentRun(runId);
            CaseResultDraft result = evaluate(
                    item, runId, facts, loopResult.sourceUrls(), loopResult.toolRounds(),
                    Duration.between(started, Instant.now()).toMillis(), totalTokens, cost, null);
            if (!store.saveCaseResult(
                    lease.evaluationRunId(), lease.leaseToken(), result, Instant.now())) {
                throw new EvaluationLeaseLostException();
            }
            return result;
        }
        catch (RuntimeException exception) {
            releaseCostSafely(loop, runId, "EVALUATION_CASE_FAILED");
            if (exception instanceof EvaluationLeaseLostException || !guard.isActive()) {
                throw new EvaluationLeaseLostException();
            }
            guard.renewNow();
            String code = failureCode(exception);
            if (!store.failAgentRun(
                    lease.evaluationRunId(), lease.leaseToken(), runId, code, Instant.now())) {
                throw new EvaluationLeaseLostException();
            }
            AgentEvaluationStore.RunFacts facts;
            try {
                facts = store.inspectAgentRun(runId);
            }
            catch (RuntimeException ignored) {
                facts = new AgentEvaluationStore.RunFacts(List.of(), "FAILED", 0, 0);
            }
            CaseResultDraft result = evaluate(item, runId, facts, List.of(), Integer.MAX_VALUE,
                    Duration.between(started, Instant.now()).toMillis(), 0, ZERO_COST, code);
            if (!store.saveCaseResult(
                    lease.evaluationRunId(), lease.leaseToken(), result, Instant.now())) {
                throw new EvaluationLeaseLostException();
            }
            metrics.caseError();
            return result;
        }
    }

    private static void releaseCostSafely(AgentLoopService loop, UUID runId, String reason) {
        try {
            loop.releaseCost(runId, reason);
        }
        catch (RuntimeException ignored) {
            // The cost ledger can reconcile a still-reserved orphan independently.
        }
    }

    private static CaseResultDraft persisted(CaseResult result) {
        return new CaseResultDraft(
                result.id(), result.caseId(), result.agentRunId(), result.status(),
                result.actualTools(), result.missingTools(), result.forbiddenToolsUsed(),
                result.sourceUrls(), result.toolSelectionCorrect(), result.planCompleted(),
                result.recoveryObserved(), result.citationRequirementsMet(), result.durationMs(),
                result.totalTokens(), result.estimatedCostCny(), result.failureCode(),
                result.detailsJson());
    }

    private CaseResultDraft evaluate(
            EvaluationCase item, UUID runId, AgentEvaluationStore.RunFacts facts,
            List<String> sources, int rounds, long durationMs, long totalTokens,
            BigDecimal cost, String failureCode) {
        List<String> missing = item.expectedTools().stream()
                .filter(expected -> !facts.actualTools().contains(expected)).toList();
        List<String> forbidden = item.forbiddenTools().stream()
                .filter(facts.actualTools()::contains).toList();
        boolean toolsCorrect = missing.isEmpty() && forbidden.isEmpty();
        boolean planCompleted = "COMPLETED".equals(facts.planStatus())
                || "SUCCEEDED".equals(facts.planStatus());
        boolean recoveryObserved = facts.retryCount() > 0 || facts.failedToolCalls() > 0;
        boolean recoveryMet = !item.expectRecovery() || recoveryObserved;
        boolean citationMet = domainsMet(item.requiredSourceDomains(), sources);
        boolean withinLimits = rounds <= item.maxToolRounds()
                && durationMs <= item.maxDurationMs()
                && totalTokens <= item.maxTokens()
                && cost.compareTo(item.maxCostCny()) <= 0;
        boolean success = failureCode == null && toolsCorrect && planCompleted
                && recoveryMet && citationMet && withinLimits;
        String details = json(Map.of(
                "toolRounds", rounds == Integer.MAX_VALUE ? -1 : rounds,
                "retryCount", facts.retryCount(),
                "failedToolCalls", facts.failedToolCalls(),
                "withinLimits", withinLimits,
                "required", item.required()));
        return new CaseResultDraft(
                UUID.randomUUID(), item.id(), runId, success ? "PASSED" : failureCode == null
                ? "FAILED" : "ERROR", facts.actualTools(), missing, forbidden, sources,
                toolsCorrect, planCompleted, recoveryObserved, citationMet,
                Math.max(0, durationMs), Math.max(0, totalTokens), cost.max(ZERO_COST),
                failureCode, details);
    }

    private Summary summarize(Dataset dataset, List<CaseOutcome> outcomes) {
        int count = outcomes.size();
        int passedCases = (int) outcomes.stream()
                .filter(item -> "PASSED".equals(item.result().status())).count();
        double successRate = ratio(passedCases, count);
        double toolAccuracy = outcomes.stream()
                .filter(item -> item.result().toolSelectionCorrect()).count() / (double) count;
        List<CaseOutcome> recovery = outcomes.stream()
                .filter(item -> item.item().expectRecovery()).toList();
        double recoveryRate = recovery.isEmpty() ? 1.0 : recovery.stream()
                .filter(item -> item.result().recoveryObserved()).count() / (double) recovery.size();
        double citationRate = outcomes.stream()
                .filter(item -> item.result().citationRequirementsMet()).count() / (double) count;
        long averageDuration = Math.round(outcomes.stream()
                .mapToLong(item -> item.result().durationMs()).average().orElse(0));
        long averageTokens = Math.round(outcomes.stream()
                .mapToLong(item -> item.result().totalTokens()).average().orElse(0));
        BigDecimal averageCost = outcomes.stream()
                .map(item -> item.result().estimatedCostCny()).reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(count), 6, RoundingMode.HALF_UP);
        boolean requiredPassed = outcomes.stream().noneMatch(item -> item.item().required()
                && !"PASSED".equals(item.result().status()));
        AgentEvaluationStore.Gate gate = dataset.gate();
        boolean gatePassed = successRate >= gate.minimumSuccessRate()
                && toolAccuracy >= gate.minimumToolAccuracy()
                && recoveryRate >= gate.minimumRecoveryRate()
                && citationRate >= gate.minimumCitationRate()
                && averageDuration <= gate.maxAverageDurationMs()
                && averageTokens <= gate.maxAverageTokens()
                && averageCost.compareTo(gate.maxAverageCostCny()) <= 0;
        return new Summary(count, passedCases, successRate, toolAccuracy, recoveryRate,
                citationRate, averageDuration, averageTokens, averageCost,
                requiredPassed && gatePassed);
    }

    private AgentEvaluationStore.DatasetDraft validateDataset(
            AgentEvaluationStore.DatasetDraft draft) {
        if (draft == null) throw new IllegalArgumentException("Dataset is required");
        String name = text(draft.name(), "Dataset name", 128);
        String description = optionalText(draft.description(), 1_000);
        AgentEvaluationStore.Gate gate = draft.gate();
        if (gate == null) throw new IllegalArgumentException("Evaluation gate is required");
        percentage(gate.minimumSuccessRate(), "minimumSuccessRate");
        percentage(gate.minimumToolAccuracy(), "minimumToolAccuracy");
        percentage(gate.minimumRecoveryRate(), "minimumRecoveryRate");
        percentage(gate.minimumCitationRate(), "minimumCitationRate");
        if (gate.maxAverageDurationMs() < 1 || gate.maxAverageTokens() < 1
                || gate.maxAverageCostCny() == null
                || gate.maxAverageCostCny().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Evaluation gate limits must be positive");
        }
        if (draft.cases() == null || draft.cases().isEmpty() || draft.cases().size() > 100) {
            throw new IllegalArgumentException("Dataset must contain between 1 and 100 cases");
        }
        Set<String> keys = new HashSet<>();
        List<CaseDraft> cases = draft.cases().stream().map(this::validateCase).toList();
        if (cases.stream().anyMatch(item -> !keys.add(item.caseKey()))) {
            throw new IllegalArgumentException("Dataset contains duplicate case keys");
        }
        return new AgentEvaluationStore.DatasetDraft(name, description, gate, cases);
    }

    private CaseDraft validateCase(CaseDraft item) {
        if (item == null) throw new IllegalArgumentException("Evaluation case is required");
        String key = text(item.caseKey(), "Case key", 96);
        if (!KEY.matcher(key).matches()) throw new IllegalArgumentException("Invalid case key");
        String question = text(item.question(), "Case question", 4_000);
        List<String> expected = tools(item.expectedTools());
        List<String> forbidden = tools(item.forbiddenTools());
        if (expected.stream().anyMatch(forbidden::contains)) {
            throw new IllegalArgumentException("A tool cannot be both expected and forbidden");
        }
        List<String> domains = item.requiredSourceDomains() == null ? List.of()
                : item.requiredSourceDomains().stream().map(value -> value.toLowerCase(Locale.ROOT).strip())
                .peek(value -> {
                    if (!DOMAIN.matcher(value).matches()) {
                        throw new IllegalArgumentException("Invalid source domain: " + value);
                    }
                }).distinct().toList();
        if (item.maxToolRounds() < 1 || item.maxToolRounds() > 12
                || item.maxDurationMs() < 1 || item.maxTokens() < 1
                || item.maxCostCny() == null || item.maxCostCny().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Case limits are invalid");
        }
        return new CaseDraft(key, question, expected, forbidden, domains,
                item.expectRecovery(), item.maxToolRounds(), item.maxDurationMs(),
                item.maxTokens(), item.maxCostCny(), item.required(), item.sourceRunId());
    }

    private List<String> tools(List<String> values) {
        if (values == null) return List.of();
        if (values.size() > 20) throw new IllegalArgumentException("Too many tools in one case");
        return values.stream().map(value -> text(value, "Tool name", 64)).distinct()
                .peek(value -> {
                    if (registry.find(value).isEmpty()) {
                        throw new IllegalArgumentException("Unknown Agent tool: " + value);
                    }
                }).toList();
    }

    private static boolean domainsMet(List<String> domains, List<String> sources) {
        if (domains.isEmpty()) return true;
        Set<String> hosts = new HashSet<>();
        for (String source : sources) {
            try {
                String host = URI.create(source).getHost();
                if (host != null) hosts.add(host.toLowerCase(Locale.ROOT));
            }
            catch (IllegalArgumentException ignored) {
                // Invalid tool URLs cannot satisfy a source-domain assertion.
            }
        }
        return domains.stream().allMatch(domain -> hosts.stream()
                .anyMatch(host -> host.equals(domain) || host.endsWith("." + domain)));
    }

    private static int positive(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private static long totalTokens(ModelUsage usage) {
        if (usage == null) return 0;
        if (usage.totalTokens() != null) return Math.max(0, usage.totalTokens());
        return (long) positive(usage.inputTokens()) + positive(usage.outputTokens());
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : numerator / (double) denominator;
    }

    private static void percentage(double value, String name) {
        if (value < 0 || value > 1) {
            throw new IllegalArgumentException(name + " must be between 0 and 1");
        }
    }

    private static String text(String value, String label, int maxLength) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        String normalized = value.strip();
        if (normalized.length() > maxLength) throw new IllegalArgumentException(label + " is too long");
        return normalized;
    }

    private static String optionalText(String value, int maxLength) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.strip();
        if (normalized.length() > maxLength) throw new IllegalArgumentException("Text is too long");
        return normalized;
    }

    private String json(Object value) {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize evaluation details", exception);
        }
    }

    private static String failureCode(RuntimeException exception) {
        if (exception instanceof AgentLoopService.AgentLoopException agent) {
            return agent.errorCode();
        }
        if (exception instanceof EvaluationUnavailableException unavailable) {
            return unavailable.code;
        }
        String name = exception.getClass().getSimpleName()
                .replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
        return name.length() > 64 ? name.substring(0, 64) : name;
    }

    private final class LeaseGuard implements AutoCloseable {
        private final EvaluationLease lease;
        private final AtomicBoolean lost = new AtomicBoolean();
        private volatile Instant validUntil;
        private final ScheduledFuture<?> heartbeat;

        private LeaseGuard(EvaluationLease lease) {
            this.lease = lease;
            this.validUntil = lease.leaseExpiresAt();
            long intervalMs = Math.max(1_000, queueProperties.heartbeatInterval().toMillis());
            heartbeat = heartbeatExecutor.scheduleWithFixedDelay(
                    this::heartbeat, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        }

        private void heartbeat() {
            if (lost.get()) return;
            Instant now = Instant.now();
            try {
                if (!store.renewEvaluationLease(
                        lease.evaluationRunId(), lease.leaseToken(),
                        queueProperties.leaseDuration(), now)) {
                    lost.set(true);
                    return;
                }
                validUntil = now.plus(queueProperties.leaseDuration());
            }
            catch (RuntimeException exception) {
                if (!now.isBefore(validUntil)) lost.set(true);
            }
        }

        private void renewNow() {
            if (lost.get()) throw new EvaluationLeaseLostException();
            Instant now = Instant.now();
            if (!store.renewEvaluationLease(
                    lease.evaluationRunId(), lease.leaseToken(),
                    queueProperties.leaseDuration(), now)) {
                lost.set(true);
                throw new EvaluationLeaseLostException();
            }
            validUntil = now.plus(queueProperties.leaseDuration());
        }

        private boolean isActive() {
            return !lost.get() && Instant.now().isBefore(validUntil);
        }

        @Override
        public void close() {
            heartbeat.cancel(false);
        }
    }

    public record Defaults(
            String modelName,
            double temperature,
            int maxOutputTokens,
            String toolContractHash) {
    }

    private record CaseOutcome(EvaluationCase item, CaseResultDraft result) { }

    private static final class EvaluationLeaseLostException extends RuntimeException {
        private EvaluationLeaseLostException() { super("EVALUATION_LEASE_LOST"); }
    }

    private static final class EvaluationUnavailableException extends RuntimeException {
        private final String code;
        private EvaluationUnavailableException(String code) {
            super(code);
            this.code = code;
        }
    }

    private static final AgentToolDispatcher.ProgressListener NOOP_LISTENER =
            new AgentToolDispatcher.ProgressListener() {
                @Override
                public void onStarted(UUID toolCallId, String toolName, int round) { }

                @Override
                public void onCompleted(
                        UUID toolCallId, String toolName, int round, int resultCount, String model) { }

                @Override
                public void onFailed(UUID toolCallId, String toolName, int round, String errorCode) { }
            };
}
