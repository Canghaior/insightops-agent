package com.jundaodsj.insightops.report.application;

import com.jundaodsj.insightops.identity.application.ActorContext;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReportDeliveryStore {

    List<ReportItem> selectReportItems(ActorContext actor, ReportQuery query);

    ReportRecord createReport(ActorContext actor, UUID reportId, ReportQuery query,
                              List<ReportItem> items, String markdown, Instant now);

    ReportPage listReports(ActorContext actor, int page, int size);

    Optional<ReportRecord> findReport(ActorContext actor, UUID reportId);

    List<DeliveryChannel> listChannels(ActorContext actor);

    DeliveryChannel createChannel(ActorContext actor, UUID channelId, String name,
                                  String endpointUrl, boolean enabled, Instant now);

    Optional<DeliveryChannel> updateChannel(ActorContext actor, UUID channelId, String name,
                                            String endpointUrl, boolean enabled, Instant now);

    boolean deleteChannel(ActorContext actor, UUID channelId, Instant now);

    Optional<DeliveryRecord> enqueueDelivery(ActorContext actor, UUID reportId,
                                             UUID channelId, Instant now);

    DeliveryPage listDeliveries(ActorContext actor, int page, int size, UUID reportId);

    Optional<DeliveryRecord> retryDelivery(ActorContext actor, UUID deliveryId, Instant now);

    List<DeliveryTask> claimDueDeliveries(Instant now, Duration leaseDuration, int limit);

    void completeDelivery(UUID deliveryId, UUID leaseToken, int responseCode,
                          long durationMs, Instant completedAt);

    void failDelivery(UUID deliveryId, UUID leaseToken, String errorCode, String errorMessage,
                      Integer responseCode, long durationMs, Instant failedAt,
                      Instant nextAttemptAt, boolean terminal);

    record ReportQuery(
            String title, Instant periodStart, Instant periodEnd, List<UUID> projectIds,
            List<String> eventTypes, int maxItems) {
        public ReportQuery {
            projectIds = projectIds == null ? List.of() : List.copyOf(projectIds);
            eventTypes = eventTypes == null ? List.of() : List.copyOf(eventTypes);
        }
    }

    record ReportItem(
            UUID analysisId, UUID projectId, String projectName, String eventType,
            String versionTag, String eventTitle, String sourceUrl, String riskLevel,
            String recommendation, String evidenceStatus, String oneLineSummary,
            List<String> majorChanges, String javaImpact, String upgradeValue,
            List<String> risks, List<String> recommendedActions, List<String> evidenceUrls,
            Instant occurredAt, Instant completedAt) {
        public ReportItem {
            majorChanges = majorChanges == null ? List.of() : List.copyOf(majorChanges);
            risks = risks == null ? List.of() : List.copyOf(risks);
            recommendedActions = recommendedActions == null ? List.of() : List.copyOf(recommendedActions);
            evidenceUrls = evidenceUrls == null ? List.of() : List.copyOf(evidenceUrls);
        }
    }

    record ReportRecord(
            UUID id, String title, String reportType, Instant periodStart, Instant periodEnd,
            List<UUID> projectIds, List<String> eventTypes, int itemCount, int highRiskCount,
            List<ReportItem> items, String markdown, Instant createdAt) {
        public ReportRecord {
            projectIds = projectIds == null ? List.of() : List.copyOf(projectIds);
            eventTypes = eventTypes == null ? List.of() : List.copyOf(eventTypes);
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    record ReportPage(List<ReportRecord> items, int page, int size, long total) { }

    record DeliveryChannel(
            UUID id, String name, String type, String endpointMasked,
            boolean enabled, Instant createdAt, Instant updatedAt) { }

    record DeliveryRecord(
            UUID id, UUID reportId, String reportTitle, UUID channelId, String channelName,
            String channelType, String endpointMasked, String status, int attempts,
            int maxAttempts, Integer responseCode, Long durationMs, String lastError,
            Instant nextAttemptAt, Instant sentAt, Instant createdAt, Instant updatedAt) { }

    record DeliveryPage(List<DeliveryRecord> items, int page, int size, long total) { }

    record DeliveryTask(
            UUID deliveryId, UUID leaseToken, UUID reportId, String reportTitle,
            Instant periodStart, Instant periodEnd, int itemCount, int highRiskCount,
            String markdownExcerpt, String endpointUrl, int attempts, int maxAttempts) { }
}
