package com.jundaodsj.insightops.agent.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Workspace-scoped quota policy, reservations and auditable cost settlement. */
public interface AgentCostGovernanceStore {

    Policy ensurePolicy(UUID workspaceId, DefaultPolicy defaults, Instant now);

    ReservationDecision reserve(ReservationRequest request);

    Optional<Reservation> findByRun(UUID runId);

    void settle(UUID runId, long actualTokens, BigDecimal actualCostCny, Instant settledAt);

    void release(UUID runId, String reason, Instant releasedAt);

    Overview overview(UUID workspaceId, LocalDate day, YearMonth month, int ledgerLimit);

    Policy updatePolicy(UUID workspaceId, UUID updatedBy, PolicyUpdate update, Instant updatedAt);

    record DefaultPolicy(
            long dailyTokenLimit,
            BigDecimal dailyCostLimitCny,
            long monthlyTokenLimit,
            BigDecimal monthlyCostLimitCny,
            int maxConcurrentRuns,
            int warningPercent,
            boolean hardLimitEnabled) {
    }

    record Policy(
            UUID workspaceId,
            boolean enabled,
            long dailyTokenLimit,
            BigDecimal dailyCostLimitCny,
            long monthlyTokenLimit,
            BigDecimal monthlyCostLimitCny,
            int maxConcurrentRuns,
            int warningPercent,
            boolean hardLimitEnabled,
            int version,
            UUID updatedBy,
            Instant updatedAt) {
    }

    record PolicyUpdate(
            boolean enabled,
            long dailyTokenLimit,
            BigDecimal dailyCostLimitCny,
            long monthlyTokenLimit,
            BigDecimal monthlyCostLimitCny,
            int maxConcurrentRuns,
            int warningPercent,
            boolean hardLimitEnabled) {
    }

    record ReservationRequest(
            UUID reservationId,
            UUID runId,
            UUID workspaceId,
            UUID userId,
            long requestedTokens,
            BigDecimal requestedCostCny,
            LocalDate usageDay,
            YearMonth usageMonth,
            Instant createdAt) {
    }

    record ReservationDecision(
            boolean allowed,
            String reason,
            Reservation reservation,
            Usage usage,
            Policy policy,
            boolean warning) {
    }

    record Reservation(
            UUID id,
            UUID runId,
            UUID workspaceId,
            UUID userId,
            long reservedTokens,
            BigDecimal reservedCostCny,
            long actualTokens,
            BigDecimal actualCostCny,
            String status,
            String reason,
            Instant createdAt,
            Instant settledAt) {
    }

    record Usage(
            long dailyTokens,
            BigDecimal dailyCostCny,
            long monthlyTokens,
            BigDecimal monthlyCostCny,
            int activeReservations) {
    }

    record LedgerEntry(
            UUID id,
            UUID runId,
            UUID userId,
            String entryType,
            long tokenDelta,
            BigDecimal costDeltaCny,
            String reason,
            Instant createdAt) {
    }

    record Overview(Policy policy, Usage usage, List<LedgerEntry> ledger) {
    }
}
