package com.jundaodsj.insightops.intelligence.application;

import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.model.application.ModelUsage;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IntelligenceStore {

    List<AnalysisTask> claimDueAnalyses(Instant now, Duration lockDuration, int limit, int dailyLimit);

    void completeAnalysis(AnalysisTask task, AnalysisResult result, ModelAudit audit, Instant completedAt);

    void failAnalysis(AnalysisTask task, String errorCode, String errorMessage,
                      Instant failedAt, Instant nextAttemptAt, boolean terminal);

    boolean requestAnalysis(UUID workspaceId, UUID eventId, UUID requestedBy, Instant now);

    AnalysisPage listAnalyses(ActorContext actor, int page, int size, UUID projectId, String riskLevel);

    Optional<AnalysisDetail> findAnalysis(ActorContext actor, UUID analysisId);

    List<AdminAnalysisStatus> adminStatuses(UUID workspaceId, int limit);

    AnalysisMetrics analysisMetrics(UUID workspaceId, Instant dayStart);

    DigestPreference getPreference(ActorContext actor);

    DigestPreference savePreference(ActorContext actor, String cadence, String timeZone,
                                    int deliveryHour, List<UUID> projectIds, Instant now);

    int refreshDueDigests(Instant now);

    DigestPage listDigests(ActorContext actor, int page, int size);

    boolean markDigestRead(ActorContext actor, UUID digestId, Instant readAt);

    NotificationPage listNotifications(ActorContext actor, int page, int size, boolean unreadOnly);

    long unreadNotifications(ActorContext actor);

    boolean markNotificationRead(ActorContext actor, UUID notificationId, Instant readAt);

    record AnalysisTask(
            UUID analysisId, UUID workspaceId, UUID projectId, UUID eventId,
            String repositoryOwner, String repositoryName, String versionTag,
            String releaseTitle, String releaseSummary, String sourceUrl,
            Instant occurredAt, int attempts, int maxAttempts, boolean automatic,
            String eventType) {
        public AnalysisTask(
                UUID analysisId, UUID workspaceId, UUID projectId, UUID eventId,
                String repositoryOwner, String repositoryName, String versionTag,
                String releaseTitle, String releaseSummary, String sourceUrl,
                Instant occurredAt, int attempts, int maxAttempts, boolean automatic) {
            this(analysisId, workspaceId, projectId, eventId, repositoryOwner, repositoryName,
                    versionTag, releaseTitle, releaseSummary, sourceUrl, occurredAt,
                    attempts, maxAttempts, automatic, "GITHUB_RELEASE");
        }
    }

    record AnalysisResult(
            String riskLevel, String recommendation, String evidenceStatus,
            String oneLineSummary, List<String> majorChanges, String javaImpact,
            String upgradeValue, List<String> risks, List<String> recommendedActions,
            List<String> evidenceUrls) {
    }

    record ModelAudit(
            String provider, String model, ModelUsage usage,
            BigDecimal estimatedCostCny, LocalDate pricingEffectiveDate) {
    }

    record AnalysisSummary(
            UUID analysisId, UUID eventId, UUID projectId, String projectName,
            String versionTag, String releaseTitle, String sourceUrl, String status,
            String riskLevel, String recommendation, String evidenceStatus,
            String oneLineSummary, Instant occurredAt, Instant completedAt) {
    }

    record AnalysisPage(List<AnalysisSummary> items, int page, int size, long total) {
    }

    record AnalysisDetail(
            AnalysisSummary summary, List<String> majorChanges, String releaseSummary,
            String javaImpact, String upgradeValue, List<String> risks,
            List<String> recommendedActions, List<String> evidenceUrls,
            String modelProvider, String modelName, Integer promptTokens,
            Integer completionTokens, BigDecimal estimatedCostCny,
            LocalDate pricingEffectiveDate, int attempts, String lastError) {
    }

    record AdminAnalysisStatus(
            UUID analysisId, UUID eventId, String projectName, String versionTag,
            String status, String riskLevel, int attempts, int maxAttempts,
            boolean automatic, Instant nextAttemptAt, Instant completedAt, String lastError) {
    }

    record AnalysisMetrics(long todayCalls, BigDecimal todayCostCny, long queued, long failed) {
    }

    record DigestPreference(String cadence, String timeZone, int deliveryHour, List<UUID> projectIds) {
    }

    record DigestSummary(
            UUID id, String cadence, Instant periodStart, Instant periodEnd,
            String title, List<AnalysisSummary> items, int itemCount,
            int highRiskCount, boolean read, Instant createdAt) {
    }

    record DigestPage(List<DigestSummary> items, int page, int size, long total, long unreadCount) {
    }

    record Notification(
            UUID id, String type, String severity, String title, String body,
            UUID entityId, boolean read, Instant createdAt, String sourceUrl) {
        public Notification(UUID id, String type, String severity, String title, String body,
                            UUID entityId, boolean read, Instant createdAt) {
            this(id,type,severity,title,body,entityId,read,createdAt,null);
        }
    }

    record NotificationPage(List<Notification> items, int page, int size, long total, long unreadCount) {
    }
}
