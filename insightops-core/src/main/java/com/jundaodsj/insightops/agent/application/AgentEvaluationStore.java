package com.jundaodsj.insightops.agent.application;

import com.jundaodsj.insightops.identity.application.ActorContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Durable datasets, candidate releases, evaluation runs and production activation state. */
public interface AgentEvaluationStore {

    Overview overview(UUID workspaceId, int runLimit);

    Optional<Dataset> findDataset(UUID workspaceId, UUID datasetId);

    Dataset createDataset(UUID workspaceId, UUID userId, DatasetDraft draft, Instant now);

    Dataset deriveDataset(
            UUID workspaceId, UUID userId, UUID baseDatasetId, CaseDraft addedCase, Instant now);

    Optional<Candidate> findCandidate(UUID workspaceId, UUID candidateId);

    Candidate createCandidate(UUID workspaceId, UUID userId, CandidateDraft draft, Instant now);

    EvaluationRun queueEvaluation(
            UUID workspaceId, UUID userId, UUID datasetId, UUID candidateId, Instant now);

    void markEvaluationRunning(UUID evaluationRunId, Instant now);

    void startAgentRun(ActorContext actor, UUID runId, String traceId, String question, Instant now);

    void completeAgentRun(
            UUID runId, String modelName, int inputTokens, int outputTokens,
            BigDecimal estimatedCostCny, List<String> sourceUrls, Instant finishedAt);

    void failAgentRun(UUID runId, String failureCode, Instant finishedAt);

    RunFacts inspectAgentRun(UUID runId);

    void saveCaseResult(UUID evaluationRunId, CaseResultDraft result, Instant now);

    EvaluationRun completeEvaluation(UUID evaluationRunId, Summary summary, Instant now);

    void failEvaluation(UUID evaluationRunId, String failureCode, Instant now);

    Optional<EvaluationRun> findEvaluation(UUID workspaceId, UUID evaluationRunId);

    Candidate activateCandidate(
            UUID workspaceId, UUID userId, UUID candidateId,
            String currentToolContractHash, String reason, Instant now);

    Optional<RuntimeProfile> activeProfile(UUID workspaceId);

    record Overview(
            List<Dataset> datasets,
            List<Candidate> candidates,
            List<EvaluationRun> recentRuns,
            RuntimeProfile activeProfile) {
    }

    record DatasetDraft(
            String name,
            String description,
            Gate gate,
            List<CaseDraft> cases) {
    }

    record Gate(
            double minimumSuccessRate,
            double minimumToolAccuracy,
            double minimumRecoveryRate,
            double minimumCitationRate,
            long maxAverageDurationMs,
            long maxAverageTokens,
            BigDecimal maxAverageCostCny) {
    }

    record CaseDraft(
            String caseKey,
            String question,
            List<String> expectedTools,
            List<String> forbiddenTools,
            List<String> requiredSourceDomains,
            boolean expectRecovery,
            int maxToolRounds,
            long maxDurationMs,
            long maxTokens,
            BigDecimal maxCostCny,
            boolean required,
            UUID sourceRunId) {
    }

    record Dataset(
            UUID id,
            UUID workspaceId,
            String name,
            String description,
            int version,
            String status,
            Gate gate,
            UUID createdBy,
            Instant createdAt,
            List<EvaluationCase> cases) {
    }

    record EvaluationCase(
            UUID id,
            UUID datasetId,
            String caseKey,
            String question,
            List<String> expectedTools,
            List<String> forbiddenTools,
            List<String> requiredSourceDomains,
            boolean expectRecovery,
            int maxToolRounds,
            long maxDurationMs,
            long maxTokens,
            BigDecimal maxCostCny,
            boolean required,
            UUID sourceRunId) {
    }

    record CandidateDraft(
            String name,
            String plannerPromptAppendix,
            String modelName,
            double temperature,
            int maxOutputTokens,
            String toolContractHash,
            UUID basedOnId) {
    }

    record Candidate(
            UUID id,
            UUID workspaceId,
            String name,
            int version,
            String status,
            String plannerPromptAppendix,
            String modelName,
            double temperature,
            int maxOutputTokens,
            String toolContractHash,
            UUID basedOnId,
            UUID createdBy,
            Instant createdAt,
            Instant evaluatedAt,
            Instant activatedAt) {
    }

    record RuntimeProfile(
            UUID candidateId,
            int version,
            String name,
            String plannerPromptAppendix,
            String modelName,
            double temperature,
            int maxOutputTokens,
            String toolContractHash,
            Instant activatedAt) {
    }

    record RunFacts(
            List<String> actualTools,
            String planStatus,
            int retryCount,
            int failedToolCalls) {
    }

    record CaseResultDraft(
            UUID id,
            UUID caseId,
            UUID agentRunId,
            String status,
            List<String> actualTools,
            List<String> missingTools,
            List<String> forbiddenToolsUsed,
            List<String> sourceUrls,
            boolean toolSelectionCorrect,
            boolean planCompleted,
            boolean recoveryObserved,
            boolean citationRequirementsMet,
            long durationMs,
            long totalTokens,
            BigDecimal estimatedCostCny,
            String failureCode,
            String detailsJson) {
    }

    record CaseResult(
            UUID id,
            UUID caseId,
            String caseKey,
            String question,
            UUID agentRunId,
            String status,
            List<String> actualTools,
            List<String> missingTools,
            List<String> forbiddenToolsUsed,
            List<String> sourceUrls,
            boolean toolSelectionCorrect,
            boolean planCompleted,
            boolean recoveryObserved,
            boolean citationRequirementsMet,
            long durationMs,
            long totalTokens,
            BigDecimal estimatedCostCny,
            String failureCode,
            String detailsJson) {
    }

    record Summary(
            int caseCount,
            int passedCaseCount,
            double successRate,
            double toolAccuracy,
            double recoveryRate,
            double citationRate,
            long averageDurationMs,
            long averageTokens,
            BigDecimal averageCostCny,
            boolean passed) {
    }

    record EvaluationRun(
            UUID id,
            UUID workspaceId,
            UUID datasetId,
            String datasetName,
            int datasetVersion,
            UUID candidateId,
            String candidateName,
            int candidateVersion,
            UUID baselineRunId,
            String status,
            Summary summary,
            Summary baselineSummary,
            String failureCode,
            UUID requestedBy,
            Instant startedAt,
            Instant finishedAt,
            Instant createdAt,
            List<CaseResult> results) {
    }
}
