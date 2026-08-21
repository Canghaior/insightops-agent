package com.jundaodsj.insightops.server.chat;

import com.jundaodsj.insightops.agent.application.AgentCostGovernanceStore;
import com.jundaodsj.insightops.infrastructure.model.DeepSeekCostEstimator;
import com.jundaodsj.insightops.model.application.ModelUsage;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;

@Service
public class AgentCostGovernanceService {

    private final AgentCostGovernanceStore store;
    private final AgentCostGovernanceProperties properties;
    private final DeepSeekCostEstimator costEstimator;
    private final AgentCostGovernanceMetrics metrics;
    private final Clock clock;

    public AgentCostGovernanceService(
            AgentCostGovernanceStore store,
            AgentCostGovernanceProperties properties,
            DeepSeekCostEstimator costEstimator,
            AgentCostGovernanceMetrics metrics) {
        this(store, properties, costEstimator, metrics, Clock.systemUTC());
    }

    AgentCostGovernanceService(
            AgentCostGovernanceStore store,
            AgentCostGovernanceProperties properties,
            DeepSeekCostEstimator costEstimator,
            AgentCostGovernanceMetrics metrics,
            Clock clock) {
        this.store = store;
        this.properties = properties;
        this.costEstimator = costEstimator;
        this.metrics = metrics;
        this.clock = clock;
    }

    public AgentCostGovernanceStore.ReservationDecision reserve(
            UUID runId, UUID workspaceId, UUID userId,
            long requestedTokens, BigDecimal requestedCostCny) {
        Instant now = clock.instant();
        AgentCostGovernanceStore.Policy policy = store.ensurePolicy(
                workspaceId, defaults(), now);
        ZoneId zone = zone();
        AgentCostGovernanceStore.ReservationDecision decision = store.reserve(
                new AgentCostGovernanceStore.ReservationRequest(
                        UUID.randomUUID(), runId, workspaceId, userId,
                        Math.max(1, requestedTokens), positive(requestedCostCny),
                        LocalDate.ofInstant(now, zone), YearMonth.from(LocalDate.ofInstant(now, zone)), now));
        if (!properties.isEnabled() || !policy.enabled()) return decision;
        metrics.reservation(decision.allowed() ? "allowed" : "rejected", decision.reason());
        if (!decision.allowed()) throw new CostQuotaException(decision.reason());
        return decision;
    }

    public void settle(UUID runId, ModelUsage usage) {
        if (runId == null || store.findByRun(runId).isEmpty()) return;
        long tokens = totalTokens(usage);
        BigDecimal cost = costEstimator.estimate(usage)
                .map(DeepSeekCostEstimator.CostEstimate::cny).orElse(BigDecimal.ZERO);
        store.settle(runId, tokens, cost, clock.instant());
        metrics.settlement("settled");
    }

    public void release(UUID runId, String reason) {
        if (runId == null || store.findByRun(runId).isEmpty()) return;
        store.release(runId, reason, clock.instant());
        metrics.settlement("released");
    }

    public AgentCostGovernanceStore.Overview overview(UUID workspaceId) {
        Instant now = clock.instant();
        store.ensurePolicy(workspaceId, defaults(), now);
        LocalDate day = LocalDate.ofInstant(now, zone());
        return store.overview(workspaceId, day, YearMonth.from(day), 100);
    }

    public AgentCostGovernanceStore.Policy update(
            UUID workspaceId, UUID updatedBy, AgentCostGovernanceStore.PolicyUpdate update) {
        validate(update);
        store.ensurePolicy(workspaceId, defaults(), clock.instant());
        return store.updatePolicy(workspaceId, updatedBy, update, clock.instant());
    }

    private AgentCostGovernanceStore.DefaultPolicy defaults() {
        return new AgentCostGovernanceStore.DefaultPolicy(
                Math.max(1, properties.getDailyTokenLimit()), positive(properties.getDailyCostLimitCny()),
                Math.max(1, properties.getMonthlyTokenLimit()), positive(properties.getMonthlyCostLimitCny()),
                Math.max(1, properties.getMaxConcurrentRuns()),
                Math.max(1, Math.min(99, properties.getWarningPercent())),
                properties.isHardLimitEnabled());
    }

    private static void validate(AgentCostGovernanceStore.PolicyUpdate update) {
        if (update == null || update.dailyTokenLimit() < 1 || update.monthlyTokenLimit() < 1
                || update.dailyCostLimitCny() == null || update.dailyCostLimitCny().signum() <= 0
                || update.monthlyCostLimitCny() == null || update.monthlyCostLimitCny().signum() <= 0
                || update.maxConcurrentRuns() < 1 || update.warningPercent() < 1
                || update.warningPercent() > 99
                || update.monthlyTokenLimit() < update.dailyTokenLimit()
                || update.monthlyCostLimitCny().compareTo(update.dailyCostLimitCny()) < 0) {
            throw new IllegalArgumentException("Invalid agent cost policy");
        }
    }

    private ZoneId zone() {
        try { return ZoneId.of(properties.getTimezone()); }
        catch (RuntimeException ignored) { return ZoneId.of("Asia/Shanghai"); }
    }

    private static BigDecimal positive(BigDecimal value) {
        return value == null || value.signum() <= 0 ? new BigDecimal("0.000001") : value;
    }

    private static long totalTokens(ModelUsage usage) {
        if (usage == null) return 0;
        if (usage.totalTokens() != null) return Math.max(0, usage.totalTokens());
        return Math.max(0, usage.inputTokens() == null ? 0 : usage.inputTokens())
                + Math.max(0, usage.outputTokens() == null ? 0 : usage.outputTokens());
    }

    public static final class CostQuotaException extends RuntimeException {
        private final String reason;

        public CostQuotaException(String reason) {
            super(reason == null ? "WORKSPACE_COST_QUOTA_EXHAUSTED" : reason);
            this.reason = reason == null ? "WORKSPACE_COST_QUOTA_EXHAUSTED" : reason;
        }

        public String reason() { return reason; }
    }
}
