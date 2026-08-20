package com.jundaodsj.insightops.knowledge.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QualityReviewStore {

    FeedbackPage listFeedback(UUID workspaceId, int page, int size, String status, String type);

    Optional<FeedbackItem> reviewFeedback(UUID workspaceId, UUID reviewerId, UUID feedbackId,
                                          String type, ReviewCommand command, Instant now);

    CandidatePage listCandidates(UUID workspaceId, int page, int size, String status);

    Optional<EvaluationCandidate> updateCandidate(UUID workspaceId, UUID candidateId,
                                                   CandidateCommand command, Instant now);

    Optional<EvaluationCandidate> decideCandidate(UUID workspaceId, UUID reviewerId,
                                                   UUID candidateId, String decision,
                                                   String note, Instant now);

    DatasetVersion createVersion(UUID workspaceId, UUID creatorId, String name,
                                 List<UUID> candidateIds, Instant now);

    List<DatasetVersion> listVersions(UUID workspaceId);

    Optional<DatasetVersion> activateVersion(UUID workspaceId, UUID reviewerId,
                                             UUID versionId, Instant now);

    Optional<DatasetSelection> datasetSelection(UUID workspaceId, UUID versionId);

    record FeedbackPage(List<FeedbackItem> items, long total, int page, int size) { }

    record FeedbackItem(
            UUID id, String type, String reviewStatus, UUID userId, String username,
            String displayName, UUID runId, UUID sessionId, String traceId, String question,
            String answer, String modelProvider, String modelName, List<String> citations,
            Boolean helpful, String reason, String comment, String citationUrl,
            Boolean citationCorrect, String reviewerNote, String reviewerDisplayName,
            Instant createdAt, Instant reviewedAt, UUID candidateId) { }

    record ReviewCommand(String decision, String note, CandidateCommand candidate) { }

    record CandidateCommand(
            String question, boolean expectedAnswerable, String expectedProject,
            String category, List<String> mustHitTerms, List<String> answerMustInclude,
            String sourceDomain) { }

    record CandidatePage(List<EvaluationCandidate> items, long total, int page, int size) { }

    record EvaluationCandidate(
            UUID id, String sourceFeedbackType, UUID sourceFeedbackId, String status,
            String question, boolean expectedAnswerable, String expectedProject,
            String category, List<String> mustHitTerms, List<String> answerMustInclude,
            String sourceDomain, String reviewerNote, String reviewerDisplayName,
            UUID datasetVersionId, Instant createdAt, Instant updatedAt) { }

    record DatasetVersion(
            UUID id, int versionNumber, String name, String status, String baseDatasetName,
            int candidateCount, UUID gateRunId, String gateStatus, Instant createdAt,
            Instant activatedAt) { }

    record DatasetSelection(String name, UUID versionId, List<DatasetCase> cases) { }

    record DatasetCase(
            String id, String question, boolean answerable, String expectedProject,
            String category, List<String> mustHitTerms, List<String> answerMustInclude,
            String sourceDomain, String status) { }
}
