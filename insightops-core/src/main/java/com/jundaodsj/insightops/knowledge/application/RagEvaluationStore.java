package com.jundaodsj.insightops.knowledge.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RagEvaluationStore {

    void start(UUID runId, UUID workspaceId, String datasetName, int caseCount,
               int generationSampleSize, Instant startedAt);

    void saveCase(UUID runId, CaseResult result);

    void complete(UUID runId, Summary summary, Instant finishedAt);

    void fail(UUID runId, String errorMessage, Instant finishedAt);

    Optional<Report> latest(UUID workspaceId);

    record CaseResult(
            String caseKey,
            String question,
            boolean expectedAnswerable,
            String expectedProject,
            boolean predictedAnswerable,
            boolean answerabilityCorrect,
            boolean projectHit,
            double reciprocalRank,
            double termCoverage,
            String retrievalMode,
            List<String> topProjects,
            List<String> sourceUrls,
            Double citationPrecision,
            Double citationCoverage,
            Double faithfulness,
            String judgeReason,
            String generatedAnswer) {
    }

    record Summary(
            double recallAtK,
            double meanReciprocalRank,
            double projectHitRate,
            double termCoverage,
            double noAnswerAccuracy,
            Double citationPrecision,
            Double citationCoverage,
            Double faithfulness,
            boolean passed,
            String modelName) {
    }

    record Report(
            UUID id,
            String datasetName,
            String status,
            int caseCount,
            int generationSampleSize,
            Summary summary,
            String errorMessage,
            Instant startedAt,
            Instant finishedAt,
            List<CaseResult> cases) {
    }
}
