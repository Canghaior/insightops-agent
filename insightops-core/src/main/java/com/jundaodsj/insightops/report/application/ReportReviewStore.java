package com.jundaodsj.insightops.report.application;

import com.jundaodsj.insightops.identity.application.ActorContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Follow-up written by the report owner. Each verification row is one immutable version. */
public interface ReportReviewStore {
    enum Origin { MODEL, HUMAN_OR_TOOL }
    enum Execution { NOT_EXECUTED, EXECUTED, BLOCKED }
    enum Impact { SUPPORTS, CONTRADICTS, INCONCLUSIVE }

    record Context(UUID reportId, UUID runId, UUID systemId, int systemRevision,
                   String requestedModel, String responseModel, String modelProvider) {}
    record VerificationInput(String question, String suggestionRef, Origin origin, Execution status,
                             String method, String environment, String observation, Impact impact,
                             String limits, String blocker, List<UUID> uploadIds,
                             UUID baselineBuildId, UUID candidateBuildId, String executedBy) {
        public VerificationInput(String question, String suggestionRef, Origin origin, Execution status,
                                 String method, String environment, String observation, Impact impact,
                                 String limits, String blocker, List<UUID> uploadIds) {
            this(question, suggestionRef, origin, status, method, environment, observation, impact,
                    limits, blocker, uploadIds, null, null, null);
        }
    }
    record DecisionInput(String choice, String reason, String reconsiderWhen, List<UUID> verificationVersionIds) {}
    record Author(UUID id, String name) {}
    record Verification(UUID id, UUID previousVersionId, int version, VerificationInput fields,
                        Author author, Instant createdAt, String buildSummary) {}
    record Decision(UUID id, DecisionInput fields, Author author, Instant createdAt) {}
    record History(Context context, List<Verification> verifications, List<Decision> decisions) {}

    Optional<Context> context(ActorContext actor, UUID reportId, boolean lock);
    List<Verification> verifications(UUID reportId);
    List<Decision> decisions(UUID reportId);
    Optional<Verification> verificationRequest(UUID reportId, UUID requestId);
    Optional<Decision> decisionRequest(UUID reportId, UUID requestId);
    Optional<Verification> verification(UUID reportId, UUID versionId);
    boolean hasCorrection(UUID reportId, UUID versionId);
    boolean isTaskResult(UUID reportId, UUID versionId);
    void addVerification(Context context, ActorContext actor, UUID id, UUID requestId,
                         UUID previousVersionId, int version, VerificationInput fields, String buildSummary);
    void addDecision(Context context, ActorContext actor, UUID id, UUID requestId, DecisionInput fields);
}
