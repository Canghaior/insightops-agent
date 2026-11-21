package com.jundaodsj.insightops.report.application;

import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.report.application.ReportReviewStore.Author;
import com.jundaodsj.insightops.report.application.ReportReviewStore.Context;
import com.jundaodsj.insightops.report.application.ReportReviewStore.Origin;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Immutable work instructions and executions. Execution observations are owned by the report verification version. */
public interface EngineeringTaskStore {
    enum Readiness { READY, NEEDS_MATERIALS }

    record TaskFields(Origin origin, String originDetail, String excerpt, Readiness readiness,
                      String question, String deliverable, String inScope, String outOfScope,
                      String facts, String inferences, String unknowns, String method, String completion,
                      String stopConditions, String environment, String missingMaterials,
                      List<UUID> verificationVersionIds, List<UUID> uploadIds) {}

    record TaskVersion(UUID id, UUID taskId, UUID previousVersionId, int version, TaskFields fields,
                       UUID reportId, UUID runId, UUID systemId, int systemRevision, UUID engineeringBaselineId,
                       Integer engineeringBaselineVersion, int excerptLine, Author author, Instant createdAt) {}

    record PatchInfo(String name, int size, String sha256) {}
    record TaskResult(UUID taskVersionId, UUID verificationVersionId, Instant executedAt, String sourceRevision,
                      String candidateRevision, String unfinished, PatchInfo patch, Author recordedBy, Instant createdAt) {}
    record History(List<TaskVersion> versions, List<TaskResult> results) {}

    List<TaskVersion> versions(UUID reportId);
    Optional<TaskVersion> version(UUID reportId, UUID versionId);
    Optional<TaskVersion> versionRequest(UUID reportId, UUID requestId);
    boolean hasRevision(UUID reportId, UUID previousVersionId);
    void insert(Context context, ActorContext actor, UUID versionId, UUID taskId, UUID requestId,
                UUID previousVersionId, int version, UUID engineeringBaselineId, Integer engineeringBaselineVersion,
                int excerptLine, TaskFields fields);

    List<TaskResult> results(UUID reportId);
    Optional<TaskResult> result(UUID reportId, UUID verificationVersionId);
    Optional<TaskResult> resultRequest(UUID reportId, UUID requestId);
    Optional<byte[]> patchBytes(UUID reportId, UUID verificationVersionId);
    void addResult(UUID reportId, UUID taskVersionId, UUID verificationVersionId, UUID requestId, ActorContext actor,
                   Instant executedAt, String sourceRevision, String candidateRevision, String unfinished,
                   PatchInfo patch, byte[] patchBytes);
}
