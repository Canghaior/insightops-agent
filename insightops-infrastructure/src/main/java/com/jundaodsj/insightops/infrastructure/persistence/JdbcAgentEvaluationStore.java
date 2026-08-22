package com.jundaodsj.insightops.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.agent.application.AgentEvaluationStore;
import com.jundaodsj.insightops.identity.application.ActorContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcAgentEvaluationStore implements AgentEvaluationStore {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcAgentEvaluationStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Overview overview(UUID workspaceId, int runLimit) {
        List<Dataset> datasets = jdbc.sql("""
                        select * from agent_evaluation_dataset
                        where workspace_id = :workspaceId
                        order by created_at desc
                        """)
                .param("workspaceId", workspaceId)
                .query((rs, row) -> dataset(rs, List.of())).list().stream()
                .map(item -> findDataset(workspaceId, item.id()).orElseThrow()).toList();
        List<Candidate> candidates = jdbc.sql("""
                        select * from agent_release_candidate
                        where workspace_id = :workspaceId
                        order by version desc
                        """)
                .param("workspaceId", workspaceId)
                .query((rs, row) -> candidate(rs)).list();
        List<UUID> runIds = jdbc.sql("""
                        select id from agent_evaluation_run
                        where workspace_id = :workspaceId
                        order by created_at desc limit :limit
                        """)
                .param("workspaceId", workspaceId).param("limit", Math.max(1, runLimit))
                .query(UUID.class).list();
        List<EvaluationRun> runs = runIds.stream()
                .map(id -> findEvaluation(workspaceId, id).orElseThrow()).toList();
        return new Overview(datasets, candidates, runs, activeProfile(workspaceId).orElse(null));
    }

    @Override
    public Optional<Dataset> findDataset(UUID workspaceId, UUID datasetId) {
        List<EvaluationCase> cases = jdbc.sql("""
                        select * from agent_evaluation_case
                        where dataset_id = :datasetId order by case_key
                        """)
                .param("datasetId", datasetId)
                .query((rs, row) -> evaluationCase(rs)).list();
        return jdbc.sql("""
                        select * from agent_evaluation_dataset
                        where id = :id and workspace_id = :workspaceId
                        """)
                .param("id", datasetId).param("workspaceId", workspaceId)
                .query((rs, row) -> dataset(rs, cases)).optional();
    }

    @Override
    @Transactional
    public Dataset createDataset(UUID workspaceId, UUID userId, DatasetDraft draft, Instant now) {
        int version = nextVersion("dataset", workspaceId, draft.name());
        UUID id = UUID.randomUUID();
        Gate gate = draft.gate();
        jdbc.sql("""
                        insert into agent_evaluation_dataset
                            (id, workspace_id, name, description, version, status,
                             minimum_success_rate, minimum_tool_accuracy, minimum_recovery_rate,
                             minimum_citation_rate, max_average_duration_ms, max_average_tokens,
                             max_average_cost_cny, created_by, created_at)
                        values
                            (:id, :workspaceId, :name, :description, :version, 'LOCKED',
                             :minimumSuccessRate, :minimumToolAccuracy, :minimumRecoveryRate,
                             :minimumCitationRate, :maxAverageDurationMs, :maxAverageTokens,
                             :maxAverageCostCny, :createdBy, :createdAt)
                        """)
                .param("id", id).param("workspaceId", workspaceId)
                .param("name", draft.name()).param("description", draft.description())
                .param("version", version)
                .param("minimumSuccessRate", gate.minimumSuccessRate())
                .param("minimumToolAccuracy", gate.minimumToolAccuracy())
                .param("minimumRecoveryRate", gate.minimumRecoveryRate())
                .param("minimumCitationRate", gate.minimumCitationRate())
                .param("maxAverageDurationMs", gate.maxAverageDurationMs())
                .param("maxAverageTokens", gate.maxAverageTokens())
                .param("maxAverageCostCny", gate.maxAverageCostCny())
                .param("createdBy", userId).param("createdAt", Timestamp.from(now)).update();
        for (CaseDraft item : draft.cases()) insertCase(id, item, now);
        return findDataset(workspaceId, id).orElseThrow();
    }

    @Override
    @Transactional
    public Dataset deriveDataset(
            UUID workspaceId, UUID userId, UUID baseDatasetId, CaseDraft addedCase, Instant now) {
        Dataset base = findDataset(workspaceId, baseDatasetId)
                .orElseThrow(() -> new IllegalArgumentException("Evaluation dataset not found"));
        List<CaseDraft> copied = new ArrayList<>(base.cases().stream().map(item -> new CaseDraft(
                item.caseKey(), item.question(), item.expectedTools(), item.forbiddenTools(),
                item.requiredSourceDomains(), item.expectRecovery(), item.maxToolRounds(),
                item.maxDurationMs(), item.maxTokens(), item.maxCostCny(), item.required(),
                item.sourceRunId())).toList());
        copied.add(addedCase);
        return createDataset(workspaceId, userId,
                new DatasetDraft(base.name(), base.description(), base.gate(), copied), now);
    }

    @Override
    public Optional<Candidate> findCandidate(UUID workspaceId, UUID candidateId) {
        return jdbc.sql("""
                        select * from agent_release_candidate
                        where id = :id and workspace_id = :workspaceId
                        """)
                .param("id", candidateId).param("workspaceId", workspaceId)
                .query((rs, row) -> candidate(rs)).optional();
    }

    @Override
    @Transactional
    public Candidate createCandidate(
            UUID workspaceId, UUID userId, CandidateDraft draft, Instant now) {
        int version = nextVersion("candidate", workspaceId, "release");
        UUID id = UUID.randomUUID();
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        params.put("workspaceId", workspaceId);
        params.put("name", draft.name());
        params.put("version", version);
        params.put("appendix", draft.plannerPromptAppendix());
        params.put("model", draft.modelName());
        params.put("temperature", draft.temperature());
        params.put("maxOutputTokens", draft.maxOutputTokens());
        params.put("toolHash", draft.toolContractHash());
        params.put("basedOnId", draft.basedOnId());
        params.put("createdBy", userId);
        params.put("createdAt", Timestamp.from(now));
        jdbc.sql("""
                        insert into agent_release_candidate
                            (id, workspace_id, name, version, status, planner_prompt_appendix,
                             model_name, temperature, max_output_tokens, tool_contract_hash,
                             based_on_id, created_by, created_at)
                        values
                            (:id, :workspaceId, :name, :version, 'DRAFT', :appendix,
                             :model, :temperature, :maxOutputTokens, :toolHash,
                             :basedOnId, :createdBy, :createdAt)
                        """).params(params).update();
        return findCandidate(workspaceId, id).orElseThrow();
    }

    @Override
    @Transactional
    public EvaluationRun queueEvaluation(
            UUID workspaceId, UUID userId, UUID datasetId, UUID candidateId, Instant now) {
        Dataset dataset = findDataset(workspaceId, datasetId)
                .orElseThrow(() -> new IllegalArgumentException("Evaluation dataset not found"));
        findCandidate(workspaceId, candidateId)
                .orElseThrow(() -> new IllegalArgumentException("Release candidate not found"));
        if (dataset.cases().isEmpty()) throw new IllegalArgumentException("Evaluation dataset is empty");
        UUID baselineRunId = jdbc.sql("""
                        select evaluation.id
                        from agent_runtime_release runtime
                        join agent_evaluation_run evaluation
                          on evaluation.candidate_id = runtime.active_candidate_id
                         and evaluation.status = 'PASSED'
                        where runtime.workspace_id = :workspaceId
                          and evaluation.candidate_id <> :candidateId
                          and evaluation.dataset_id = :datasetId
                        order by evaluation.finished_at desc limit 1
                        """)
                .param("workspaceId", workspaceId).param("candidateId", candidateId)
                .param("datasetId", datasetId)
                .query(UUID.class).optional().orElse(null);
        UUID id = UUID.randomUUID();
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        params.put("workspaceId", workspaceId);
        params.put("datasetId", datasetId);
        params.put("candidateId", candidateId);
        params.put("baselineRunId", baselineRunId);
        params.put("requestedBy", userId);
        params.put("caseCount", dataset.cases().size());
        params.put("createdAt", Timestamp.from(now));
        jdbc.sql("""
                        insert into agent_evaluation_run
                            (id, workspace_id, dataset_id, candidate_id, baseline_run_id,
                             requested_by, status, case_count, created_at)
                        values
                            (:id, :workspaceId, :datasetId, :candidateId, :baselineRunId,
                             :requestedBy, 'QUEUED', :caseCount, :createdAt)
                        """).params(params).update();
        return findEvaluation(workspaceId, id).orElseThrow();
    }

    @Override
    @Transactional
    public List<EvaluationLease> claimEvaluations(
            String workerId, int limit, int maxAttempts, Duration leaseDuration, Instant now) {
        int safeLimit = Math.max(1, limit);
        int safeMaxAttempts = Math.max(1, maxAttempts);
        Timestamp claimedAt = Timestamp.from(now);
        Timestamp expiresAt = Timestamp.from(now.plus(leaseDuration));
        jdbc.sql("""
                        with exhausted as (
                            update agent_evaluation_run
                            set status = 'FAILED', failure_code = 'EVALUATION_ATTEMPTS_EXHAUSTED',
                                finished_at = :now, lease_token = null, lease_expires_at = null
                            where status = 'RUNNING'
                              and (lease_expires_at is null or lease_expires_at <= :now)
                              and attempt_count >= :maxAttempts
                            returning candidate_id
                        )
                        update agent_release_candidate candidate
                        set status = case when candidate.status = 'ACTIVE'
                                then candidate.status else 'FAILED' end,
                            evaluated_at = :now
                        where candidate.id in (select candidate_id from exhausted)
                        """)
                .param("now", claimedAt).param("maxAttempts", safeMaxAttempts).update();
        List<ClaimableEvaluation> claimable = jdbc.sql("""
                        select id, workspace_id, requested_by, attempt_count
                        from agent_evaluation_run
                        where (status = 'QUEUED' or (status = 'RUNNING'
                               and (lease_expires_at is null or lease_expires_at <= :now)))
                          and attempt_count < :maxAttempts
                        order by created_at, id
                        for update skip locked
                        limit :limit
                        """)
                .param("now", claimedAt).param("maxAttempts", safeMaxAttempts)
                .param("limit", safeLimit)
                .query((rs, row) -> new ClaimableEvaluation(
                        rs.getObject("id", UUID.class),
                        rs.getObject("workspace_id", UUID.class),
                        rs.getObject("requested_by", UUID.class),
                        rs.getInt("attempt_count"))).list();
        List<EvaluationLease> leases = new ArrayList<>();
        for (ClaimableEvaluation item : claimable) {
            UUID token = UUID.randomUUID();
            int updated = jdbc.sql("""
                            update agent_evaluation_run
                            set status = 'RUNNING', attempt_count = attempt_count + 1,
                                claimed_by = :workerId, lease_token = :leaseToken,
                                heartbeat_at = :now, lease_expires_at = :expiresAt,
                                started_at = coalesce(started_at, :now), failure_code = null
                            where id = :id
                            """)
                    .param("id", item.id()).param("workerId", workerId)
                    .param("leaseToken", token).param("now", claimedAt)
                    .param("expiresAt", expiresAt).update();
            if (updated == 1) {
                leases.add(new EvaluationLease(
                        item.id(), item.workspaceId(), item.requestedBy(), token,
                        workerId, item.attemptCount() + 1, expiresAt.toInstant()));
            }
        }
        return List.copyOf(leases);
    }

    @Override
    public boolean renewEvaluationLease(
            UUID evaluationRunId, UUID leaseToken, Duration leaseDuration, Instant now) {
        return jdbc.sql("""
                        update agent_evaluation_run
                        set heartbeat_at = :now, lease_expires_at = :expiresAt
                        where id = :id and status = 'RUNNING' and lease_token = :leaseToken
                          and lease_expires_at > :now
                        """)
                .param("id", evaluationRunId).param("leaseToken", leaseToken)
                .param("now", Timestamp.from(now))
                .param("expiresAt", Timestamp.from(now.plus(leaseDuration))).update() == 1;
    }

    @Override
    @Transactional
    public List<UUID> prepareEvaluationAttempt(
            UUID evaluationRunId, UUID leaseToken, Instant now) {
        List<UUID> orphaned = jdbc.sql("""
                        select run.id from agent_run run
                        join agent_evaluation_run evaluation
                          on evaluation.id = run.evaluation_run_id
                        where evaluation.id = :evaluationRunId
                          and evaluation.status = 'RUNNING'
                          and evaluation.lease_token = :leaseToken
                          and evaluation.lease_expires_at > :now
                          and run.status = 'RUNNING'
                        for update of run
                        """)
                .param("evaluationRunId", evaluationRunId).param("leaseToken", leaseToken)
                .param("now", Timestamp.from(now))
                .query(UUID.class).list();
        for (UUID runId : orphaned) {
            jdbc.sql("""
                            update agent_run
                            set status = 'FAILED', failure_code = 'EVALUATION_WORKER_LOST',
                                finished_at = :now
                            where id = :id and status = 'RUNNING'
                            """)
                    .param("id", runId).param("now", Timestamp.from(now)).update();
        }
        return List.copyOf(orphaned);
    }

    @Override
    public boolean startAgentRun(
            ActorContext actor, UUID evaluationRunId, UUID evaluationCaseId, UUID leaseToken,
            UUID runId, String traceId, String question, Instant now) {
        return jdbc.sql("""
                        insert into agent_run
                            (id, workspace_id, owner_user_id, session_id, trace_id, status,
                             question, started_at, created_at, run_kind,
                             evaluation_run_id, evaluation_case_id)
                        select :id, :workspaceId, :userId, null, :traceId, 'RUNNING',
                               :question, :now, :now, 'EVALUATION',
                               :evaluationRunId, :evaluationCaseId
                        from agent_evaluation_run evaluation
                        where evaluation.id = :evaluationRunId
                          and evaluation.status = 'RUNNING'
                          and evaluation.lease_token = :leaseToken
                          and evaluation.lease_expires_at > :now
                        """)
                .param("id", runId).param("workspaceId", actor.workspaceId())
                .param("userId", actor.userId()).param("traceId", traceId)
                .param("question", question).param("now", Timestamp.from(now))
                .param("evaluationRunId", evaluationRunId)
                .param("evaluationCaseId", evaluationCaseId)
                .param("leaseToken", leaseToken).update() == 1;
    }

    @Override
    public boolean completeAgentRun(
            UUID evaluationRunId, UUID leaseToken, UUID runId,
            String modelName, int inputTokens, int outputTokens,
            BigDecimal estimatedCostCny, List<String> sourceUrls, Instant finishedAt) {
        return jdbc.sql("""
                        update agent_run run
                        set status = 'SUCCEEDED', answer = '[agent evaluation completed]',
                            citations = cast(:citations as jsonb), model_provider = 'deepseek',
                            model_name = :modelName, prompt_tokens = :inputTokens,
                            completion_tokens = :outputTokens, estimated_cost_cny = :cost,
                            finished_at = :finishedAt
                        from agent_evaluation_run evaluation
                        where run.id = :id and run.status = 'RUNNING'
                          and run.run_kind = 'EVALUATION'
                          and evaluation.id = :evaluationRunId
                          and evaluation.lease_token = :leaseToken
                          and evaluation.status = 'RUNNING'
                          and evaluation.lease_expires_at > :finishedAt
                        """)
                .param("id", runId).param("citations", json(sourceUrls))
                .param("modelName", modelName).param("inputTokens", inputTokens)
                .param("outputTokens", outputTokens).param("cost", estimatedCostCny)
                .param("finishedAt", Timestamp.from(finishedAt))
                .param("evaluationRunId", evaluationRunId)
                .param("leaseToken", leaseToken).update() == 1;
    }

    @Override
    public boolean failAgentRun(
            UUID evaluationRunId, UUID leaseToken, UUID runId,
            String failureCode, Instant finishedAt) {
        return jdbc.sql("""
                        update agent_run run
                        set status = 'FAILED', failure_code = :failureCode, finished_at = :finishedAt
                        from agent_evaluation_run evaluation
                        where run.id = :id and run.status = 'RUNNING'
                          and run.run_kind = 'EVALUATION'
                          and evaluation.id = :evaluationRunId
                          and evaluation.lease_token = :leaseToken
                          and evaluation.status = 'RUNNING'
                          and evaluation.lease_expires_at > :finishedAt
                        """)
                .param("id", runId).param("failureCode", failureCode)
                .param("finishedAt", Timestamp.from(finishedAt))
                .param("evaluationRunId", evaluationRunId)
                .param("leaseToken", leaseToken).update() == 1;
    }

    @Override
    public RunFacts inspectAgentRun(UUID runId) {
        List<String> tools = jdbc.sql("""
                        select tool_name from tool_call
                        where run_id = :runId order by created_at, id
                        """).param("runId", runId).query(String.class).list();
        String planStatus = jdbc.sql("""
                        select status from agent_plan where run_id = :runId
                        order by version desc limit 1
                        """).param("runId", runId).query(String.class).optional().orElse("MISSING");
        int retries = jdbc.sql("""
                        select count(*) from tool_call_attempt attempt
                        join tool_call call on call.id = attempt.tool_call_id
                        where call.run_id = :runId and attempt.attempt_no > 1
                        """).param("runId", runId).query(Integer.class).single();
        int failures = jdbc.sql("""
                        select count(*) from tool_call
                        where run_id = :runId and status in ('FAILED', 'TIMED_OUT', 'CANCELLED')
                        """).param("runId", runId).query(Integer.class).single();
        return new RunFacts(List.copyOf(new LinkedHashSet<>(tools)), planStatus, retries, failures);
    }

    @Override
    public boolean saveCaseResult(
            UUID evaluationRunId, UUID leaseToken, CaseResultDraft result, Instant now) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", result.id());
        params.put("evaluationRunId", evaluationRunId);
        params.put("caseId", result.caseId());
        params.put("agentRunId", result.agentRunId());
        params.put("status", result.status());
        params.put("actualTools", json(result.actualTools()));
        params.put("missingTools", json(result.missingTools()));
        params.put("forbiddenToolsUsed", json(result.forbiddenToolsUsed()));
        params.put("sourceUrls", json(result.sourceUrls()));
        params.put("toolCorrect", result.toolSelectionCorrect());
        params.put("planCompleted", result.planCompleted());
        params.put("recoveryObserved", result.recoveryObserved());
        params.put("citationsMet", result.citationRequirementsMet());
        params.put("durationMs", result.durationMs());
        params.put("totalTokens", result.totalTokens());
        params.put("cost", result.estimatedCostCny());
        params.put("failureCode", result.failureCode());
        params.put("details", result.detailsJson());
        params.put("createdAt", Timestamp.from(now));
        params.put("leaseToken", leaseToken);
        return jdbc.sql("""
                        insert into agent_evaluation_case_result
                            (id, evaluation_run_id, case_id, agent_run_id, status,
                             actual_tools, missing_tools, forbidden_tools_used, source_urls,
                             tool_selection_correct, plan_completed, recovery_observed,
                             citation_requirements_met, duration_ms, total_tokens,
                             estimated_cost_cny, failure_code, details, created_at)
                        select :id, :evaluationRunId, :caseId, :agentRunId, :status,
                             cast(:actualTools as jsonb), cast(:missingTools as jsonb),
                             cast(:forbiddenToolsUsed as jsonb), cast(:sourceUrls as jsonb),
                             :toolCorrect, :planCompleted, :recoveryObserved,
                             :citationsMet, :durationMs, :totalTokens,
                             :cost, :failureCode, cast(:details as jsonb), :createdAt
                        from agent_evaluation_run evaluation
                        where evaluation.id = :evaluationRunId
                          and evaluation.status = 'RUNNING'
                          and evaluation.lease_token = :leaseToken
                          and evaluation.lease_expires_at > :createdAt
                        on conflict (evaluation_run_id, case_id) do nothing
                        """).params(params).update() == 1;
    }

    @Override
    @Transactional
    public boolean completeEvaluation(
            UUID evaluationRunId, UUID leaseToken, Summary summary, Instant now) {
        int updated = jdbc.sql("""
                        update agent_evaluation_run
                        set status = :status, passed_case_count = :passedCaseCount,
                            success_rate = :successRate, tool_accuracy = :toolAccuracy,
                            recovery_rate = :recoveryRate, citation_rate = :citationRate,
                            average_duration_ms = :averageDurationMs,
                            average_tokens = :averageTokens, average_cost_cny = :averageCostCny,
                            finished_at = :finishedAt, heartbeat_at = :finishedAt,
                            lease_token = null, lease_expires_at = null
                        where id = :id and status = 'RUNNING'
                          and lease_token = :leaseToken
                          and lease_expires_at > :finishedAt
                        """)
                .param("id", evaluationRunId).param("status", summary.passed() ? "PASSED" : "FAILED")
                .param("leaseToken", leaseToken)
                .param("passedCaseCount", summary.passedCaseCount())
                .param("successRate", summary.successRate()).param("toolAccuracy", summary.toolAccuracy())
                .param("recoveryRate", summary.recoveryRate()).param("citationRate", summary.citationRate())
                .param("averageDurationMs", summary.averageDurationMs())
                .param("averageTokens", summary.averageTokens())
                .param("averageCostCny", summary.averageCostCny())
                .param("finishedAt", Timestamp.from(now)).update();
        if (updated != 1) {
            return false;
        }
        jdbc.sql("""
                        update agent_release_candidate candidate
                        set status = case when candidate.status = 'ACTIVE'
                            then candidate.status else :status end,
                            evaluated_at = :now
                        from agent_evaluation_run evaluation
                        where evaluation.id = :evaluationRunId and candidate.id = evaluation.candidate_id
                        """)
                .param("evaluationRunId", evaluationRunId)
                .param("status", summary.passed() ? "PASSED" : "FAILED")
                .param("now", Timestamp.from(now)).update();
        return true;
    }

    @Override
    @Transactional
    public boolean failEvaluation(
            UUID evaluationRunId, UUID leaseToken, String failureCode, Instant now) {
        int updated = jdbc.sql("""
                        update agent_evaluation_run
                        set status = 'FAILED', failure_code = :failureCode,
                            finished_at = :now, heartbeat_at = :now,
                            lease_token = null, lease_expires_at = null
                        where id = :id and status = 'RUNNING' and lease_token = :leaseToken
                          and lease_expires_at > :now
                        """).param("id", evaluationRunId).param("failureCode", failureCode)
                .param("leaseToken", leaseToken)
                .param("now", Timestamp.from(now)).update();
        if (updated != 1) {
            return false;
        }
        jdbc.sql("""
                        update agent_release_candidate candidate
                        set status = case when candidate.status = 'ACTIVE'
                            then candidate.status else 'FAILED' end,
                            evaluated_at = :now
                        from agent_evaluation_run evaluation
                        where evaluation.id = :evaluationRunId and candidate.id = evaluation.candidate_id
                        """).param("evaluationRunId", evaluationRunId)
                .param("now", Timestamp.from(now)).update();
        return true;
    }

    @Override
    public Optional<EvaluationRun> findEvaluation(UUID workspaceId, UUID evaluationRunId) {
        List<CaseResult> results = jdbc.sql("""
                        select result.*, item.case_key, item.question
                        from agent_evaluation_case_result result
                        join agent_evaluation_case item on item.id = result.case_id
                        where result.evaluation_run_id = :runId
                        order by item.case_key
                        """).param("runId", evaluationRunId)
                .query((rs, row) -> caseResult(rs)).list();
        return jdbc.sql("""
                        select evaluation.*, dataset.name as dataset_name,
                               dataset.version as dataset_version,
                               candidate.name as candidate_name,
                               candidate.version as candidate_version
                        from agent_evaluation_run evaluation
                        join agent_evaluation_dataset dataset on dataset.id = evaluation.dataset_id
                        join agent_release_candidate candidate on candidate.id = evaluation.candidate_id
                        where evaluation.id = :id and evaluation.workspace_id = :workspaceId
                        """).param("id", evaluationRunId).param("workspaceId", workspaceId)
                .query((rs, row) -> evaluationRun(rs, results)).optional();
    }

    @Override
    @Transactional
    public Candidate activateCandidate(
            UUID workspaceId, UUID userId, UUID candidateId,
            String currentToolContractHash, String reason, Instant now) {
        jdbc.sql("""
                        select 1 from (select pg_advisory_xact_lock(hashtext(:key))) locked""")
                .param("key", "agent-release:" + workspaceId).query(Integer.class).single();
        Candidate candidate = jdbc.sql("""
                        select * from agent_release_candidate
                        where id = :id and workspace_id = :workspaceId for update
                        """).param("id", candidateId).param("workspaceId", workspaceId)
                .query((rs, row) -> candidate(rs)).optional()
                .orElseThrow(() -> new IllegalArgumentException("Release candidate not found"));
        if (!candidate.toolContractHash().equals(currentToolContractHash)) {
            throw new IllegalStateException("Tool contract changed; run evaluation again");
        }
        String latestStatus = jdbc.sql("""
                        select status from agent_evaluation_run
                        where candidate_id = :candidateId
                        order by created_at desc, id desc limit 1
                        """).param("candidateId", candidateId)
                .query(String.class).optional().orElse(null);
        if (!"PASSED".equals(latestStatus)) {
            throw new IllegalStateException("Candidate must pass its latest evaluation before activation");
        }
        UUID previous = jdbc.sql("""
                        select active_candidate_id from agent_runtime_release
                        where workspace_id = :workspaceId for update
                        """).param("workspaceId", workspaceId).query(UUID.class).optional().orElse(null);
        if (previous != null && !previous.equals(candidateId)) {
            jdbc.sql("""
                            update agent_release_candidate set status = 'RETIRED'
                            where id = :id and status = 'ACTIVE'
                            """).param("id", previous).update();
        }
        jdbc.sql("""
                        update agent_release_candidate
                        set status = 'ACTIVE', activated_at = :now where id = :id
                        """).param("id", candidateId).param("now", Timestamp.from(now)).update();
        Map<String, Object> params = new HashMap<>();
        params.put("workspaceId", workspaceId);
        params.put("candidateId", candidateId);
        params.put("updatedBy", userId);
        params.put("now", Timestamp.from(now));
        jdbc.sql("""
                        insert into agent_runtime_release
                            (workspace_id, active_candidate_id, version, updated_by, updated_at)
                        values (:workspaceId, :candidateId, 1, :updatedBy, :now)
                        on conflict (workspace_id) do update
                        set active_candidate_id = excluded.active_candidate_id,
                            version = agent_runtime_release.version + 1,
                            updated_by = excluded.updated_by, updated_at = excluded.updated_at
                        """).params(params).update();
        params.put("id", UUID.randomUUID());
        params.put("previousId", previous);
        params.put("reason", reason);
        jdbc.sql("""
                        insert into agent_release_activation_audit
                            (id, workspace_id, previous_candidate_id, activated_candidate_id,
                             activated_by, reason, created_at)
                        values (:id, :workspaceId, :previousId, :candidateId,
                                :updatedBy, :reason, :now)
                        """).params(params).update();
        return findCandidate(workspaceId, candidateId).orElseThrow();
    }

    @Override
    public Optional<RuntimeProfile> activeProfile(UUID workspaceId) {
        return jdbc.sql("""
                        select candidate.id, candidate.name, candidate.planner_prompt_appendix,
                               candidate.model_name, candidate.temperature,
                               candidate.max_output_tokens, candidate.tool_contract_hash,
                               candidate.version as candidate_version,
                               runtime.version, runtime.updated_at
                        from agent_runtime_release runtime
                        join agent_release_candidate candidate on candidate.id = runtime.active_candidate_id
                        where runtime.workspace_id = :workspaceId and candidate.status = 'ACTIVE'
                        """).param("workspaceId", workspaceId)
                .query((rs, row) -> new RuntimeProfile(
                        rs.getObject("id", UUID.class), rs.getInt("candidate_version"), rs.getString("name"),
                        rs.getString("planner_prompt_appendix"), rs.getString("model_name"),
                        rs.getDouble("temperature"), rs.getInt("max_output_tokens"),
                        rs.getString("tool_contract_hash"), instant(rs, "updated_at"))).optional();
    }

    private int nextVersion(String type, UUID workspaceId, String name) {
        String key = "agent-evaluation:" + type + ":" + workspaceId + ":" + name;
        jdbc.sql("""
                        select 1 from (select pg_advisory_xact_lock(hashtext(:key))) locked""")
                .param("key", key).query(Integer.class).single();
        String table = "dataset".equals(type) ? "agent_evaluation_dataset" : "agent_release_candidate";
        String condition = "dataset".equals(type) ? " and name = :name" : "";
        JdbcClient.StatementSpec query = jdbc.sql("select coalesce(max(version), 0) + 1 from "
                        + table + " where workspace_id = :workspaceId" + condition)
                .param("workspaceId", workspaceId);
        if ("dataset".equals(type)) query = query.param("name", name);
        return query.query(Integer.class).single();
    }

    private void insertCase(UUID datasetId, CaseDraft item, Instant now) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", UUID.randomUUID());
        params.put("datasetId", datasetId);
        params.put("caseKey", item.caseKey());
        params.put("question", item.question());
        params.put("expectedTools", json(item.expectedTools()));
        params.put("forbiddenTools", json(item.forbiddenTools()));
        params.put("domains", json(item.requiredSourceDomains()));
        params.put("expectRecovery", item.expectRecovery());
        params.put("maxToolRounds", item.maxToolRounds());
        params.put("maxDurationMs", item.maxDurationMs());
        params.put("maxTokens", item.maxTokens());
        params.put("maxCostCny", item.maxCostCny());
        params.put("required", item.required());
        params.put("sourceRunId", item.sourceRunId());
        params.put("createdAt", Timestamp.from(now));
        jdbc.sql("""
                        insert into agent_evaluation_case
                            (id, dataset_id, case_key, question, expected_tools, forbidden_tools,
                             required_source_domains, expect_recovery, max_tool_rounds,
                             max_duration_ms, max_tokens, max_cost_cny, required,
                             source_run_id, created_at)
                        values
                            (:id, :datasetId, :caseKey, :question, cast(:expectedTools as jsonb),
                             cast(:forbiddenTools as jsonb), cast(:domains as jsonb),
                             :expectRecovery, :maxToolRounds, :maxDurationMs, :maxTokens,
                             :maxCostCny, :required, :sourceRunId, :createdAt)
                        """).params(params).update();
    }

    private Dataset dataset(ResultSet rs, List<EvaluationCase> cases) throws SQLException {
        return new Dataset(
                rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class),
                rs.getString("name"), rs.getString("description"), rs.getInt("version"),
                rs.getString("status"), new Gate(
                rs.getDouble("minimum_success_rate"), rs.getDouble("minimum_tool_accuracy"),
                rs.getDouble("minimum_recovery_rate"), rs.getDouble("minimum_citation_rate"),
                rs.getLong("max_average_duration_ms"), rs.getLong("max_average_tokens"),
                rs.getBigDecimal("max_average_cost_cny")),
                rs.getObject("created_by", UUID.class), instant(rs, "created_at"), cases);
    }

    private EvaluationCase evaluationCase(ResultSet rs) throws SQLException {
        return new EvaluationCase(
                rs.getObject("id", UUID.class), rs.getObject("dataset_id", UUID.class),
                rs.getString("case_key"), rs.getString("question"),
                strings(rs.getString("expected_tools")), strings(rs.getString("forbidden_tools")),
                strings(rs.getString("required_source_domains")), rs.getBoolean("expect_recovery"),
                rs.getInt("max_tool_rounds"), rs.getLong("max_duration_ms"),
                rs.getLong("max_tokens"), rs.getBigDecimal("max_cost_cny"),
                rs.getBoolean("required"), rs.getObject("source_run_id", UUID.class));
    }

    private Candidate candidate(ResultSet rs) throws SQLException {
        return new Candidate(
                rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class),
                rs.getString("name"), rs.getInt("version"), rs.getString("status"),
                rs.getString("planner_prompt_appendix"), rs.getString("model_name"),
                rs.getDouble("temperature"), rs.getInt("max_output_tokens"),
                rs.getString("tool_contract_hash"), rs.getObject("based_on_id", UUID.class),
                rs.getObject("created_by", UUID.class), instant(rs, "created_at"),
                instant(rs, "evaluated_at"), instant(rs, "activated_at"));
    }

    private EvaluationRun evaluationRun(ResultSet rs, List<CaseResult> results) throws SQLException {
        UUID baselineId = rs.getObject("baseline_run_id", UUID.class);
        Summary baseline = baselineId == null ? null : jdbc.sql("""
                        select * from agent_evaluation_run where id = :id
                        """).param("id", baselineId).query((base, row) -> summary(base)).optional().orElse(null);
        return new EvaluationRun(
                rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class),
                rs.getObject("dataset_id", UUID.class), rs.getString("dataset_name"),
                rs.getInt("dataset_version"), rs.getObject("candidate_id", UUID.class),
                rs.getString("candidate_name"), rs.getInt("candidate_version"), baselineId,
                rs.getString("status"), summary(rs), baseline, rs.getString("failure_code"),
                rs.getObject("requested_by", UUID.class), rs.getInt("attempt_count"),
                rs.getString("claimed_by"), instant(rs, "heartbeat_at"),
                instant(rs, "lease_expires_at"), instant(rs, "started_at"),
                instant(rs, "finished_at"), instant(rs, "created_at"), results);
    }

    private Summary summary(ResultSet rs) throws SQLException {
        BigDecimal success = rs.getBigDecimal("success_rate");
        if (success == null) return null;
        return new Summary(
                rs.getInt("case_count"), rs.getInt("passed_case_count"), success.doubleValue(),
                rs.getBigDecimal("tool_accuracy").doubleValue(),
                rs.getBigDecimal("recovery_rate").doubleValue(),
                rs.getBigDecimal("citation_rate").doubleValue(),
                rs.getLong("average_duration_ms"), rs.getLong("average_tokens"),
                rs.getBigDecimal("average_cost_cny"), "PASSED".equals(rs.getString("status")));
    }

    private CaseResult caseResult(ResultSet rs) throws SQLException {
        return new CaseResult(
                rs.getObject("id", UUID.class), rs.getObject("case_id", UUID.class),
                rs.getString("case_key"), rs.getString("question"),
                rs.getObject("agent_run_id", UUID.class), rs.getString("status"),
                strings(rs.getString("actual_tools")), strings(rs.getString("missing_tools")),
                strings(rs.getString("forbidden_tools_used")), strings(rs.getString("source_urls")),
                rs.getBoolean("tool_selection_correct"), rs.getBoolean("plan_completed"),
                rs.getBoolean("recovery_observed"), rs.getBoolean("citation_requirements_met"),
                rs.getLong("duration_ms"), rs.getLong("total_tokens"),
                rs.getBigDecimal("estimated_cost_cny"), rs.getString("failure_code"),
                rs.getString("details"));
    }

    private List<String> strings(String value) {
        try {
            return value == null ? List.of() : List.copyOf(json.readValue(value, STRING_LIST));
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid stored string list", exception);
        }
    }

    private String json(Object value) {
        try {
            return json.writeValueAsString(value == null ? List.of() : value);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Value cannot be serialized", exception);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private record ClaimableEvaluation(
            UUID id, UUID workspaceId, UUID requestedBy, int attemptCount) { }

}
