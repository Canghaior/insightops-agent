package com.jundaodsj.insightops.infrastructure.persistence;

import com.jundaodsj.insightops.agent.application.AgentCostGovernanceStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcAgentCostGovernanceStore implements AgentCostGovernanceStore {

    private final JdbcClient jdbcClient;

    public JdbcAgentCostGovernanceStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Policy ensurePolicy(UUID workspaceId, DefaultPolicy defaults, Instant now) {
        jdbcClient.sql("""
                        insert into agent_cost_policy (
                            workspace_id, enabled, daily_token_limit, daily_cost_limit_cny,
                            monthly_token_limit, monthly_cost_limit_cny, max_concurrent_runs,
                            warning_percent, hard_limit_enabled, version, created_at, updated_at
                        ) values (
                            :workspaceId, true, :dailyTokens, :dailyCost, :monthlyTokens,
                            :monthlyCost, :maxConcurrent, :warningPercent, :hardLimit, 1, :now, :now
                        ) on conflict (workspace_id) do nothing
                        """)
                .param("workspaceId", workspaceId)
                .param("dailyTokens", defaults.dailyTokenLimit())
                .param("dailyCost", defaults.dailyCostLimitCny())
                .param("monthlyTokens", defaults.monthlyTokenLimit())
                .param("monthlyCost", defaults.monthlyCostLimitCny())
                .param("maxConcurrent", defaults.maxConcurrentRuns())
                .param("warningPercent", defaults.warningPercent())
                .param("hardLimit", defaults.hardLimitEnabled())
                .param("now", timestamp(now)).update();
        return policy(workspaceId, false);
    }

    @Override
    @Transactional
    public ReservationDecision reserve(ReservationRequest request) {
        Optional<Reservation> existing = findByRun(request.runId());
        if (existing.isPresent()) {
            Reservation reservation = existing.get();
            Policy policy = policy(request.workspaceId(), true);
            Usage usage = usage(request.workspaceId(), request.usageDay(), request.usageMonth());
            return new ReservationDecision(!"REJECTED".equals(reservation.status()), reservation.reason(),
                    reservation, usage, policy, warning(policy, usage));
        }

        Policy policy = policy(request.workspaceId(), true);
        Usage before = usage(request.workspaceId(), request.usageDay(), request.usageMonth());
        Usage projected = new Usage(
                before.dailyTokens() + request.requestedTokens(),
                before.dailyCostCny().add(request.requestedCostCny()),
                before.monthlyTokens() + request.requestedTokens(),
                before.monthlyCostCny().add(request.requestedCostCny()),
                before.activeReservations() + 1);
        String rejectedReason = rejection(policy, projected);
        boolean rejected = policy.enabled() && policy.hardLimitEnabled() && rejectedReason != null;
        String status = rejected ? "REJECTED" : "RESERVED";
        jdbcClient.sql("""
                        insert into agent_cost_reservation (
                            id, run_id, workspace_id, user_id, usage_day, usage_month,
                            reserved_tokens, reserved_cost_cny, status, reason, created_at
                        ) values (
                            :id, :runId, :workspaceId, :userId, :usageDay, :usageMonth,
                            :tokens, :cost, :status, :reason, :createdAt
                        )
                        """)
                .param("id", request.reservationId()).param("runId", request.runId())
                .param("workspaceId", request.workspaceId()).param("userId", request.userId())
                .param("usageDay", request.usageDay()).param("usageMonth", request.usageMonth().atDay(1))
                .param("tokens", request.requestedTokens()).param("cost", request.requestedCostCny())
                .param("status", status).param("reason", rejectedReason)
                .param("createdAt", timestamp(request.createdAt())).update();
        ledger(request, rejected ? "REJECT" : "RESERVE",
                rejected ? 0 : request.requestedTokens(),
                rejected ? BigDecimal.ZERO : request.requestedCostCny(), rejectedReason);
        Reservation reservation = findByRun(request.runId()).orElseThrow();
        return new ReservationDecision(!rejected, rejectedReason, reservation,
                rejected ? before : projected, policy,
                !rejected && warning(policy, projected));
    }

    @Override
    public Optional<Reservation> findByRun(UUID runId) {
        return jdbcClient.sql("""
                        select id, run_id, workspace_id, user_id, reserved_tokens,
                               reserved_cost_cny, actual_tokens, actual_cost_cny,
                               status, reason, created_at, settled_at
                        from agent_cost_reservation where run_id = :runId
                        """)
                .param("runId", runId)
                .query((rs, rowNum) -> new Reservation(
                        rs.getObject("id", UUID.class), rs.getObject("run_id", UUID.class),
                        rs.getObject("workspace_id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getLong("reserved_tokens"), rs.getBigDecimal("reserved_cost_cny"),
                        rs.getLong("actual_tokens"), rs.getBigDecimal("actual_cost_cny"),
                        rs.getString("status"), rs.getString("reason"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        instant(rs.getObject("settled_at", OffsetDateTime.class))))
                .optional();
    }

    @Override
    @Transactional
    public void settle(UUID runId, long actualTokens, BigDecimal actualCostCny, Instant settledAt) {
        ReservationRow row = lockReservation(runId);
        if (!"RESERVED".equals(row.status())) return;
        long tokens = Math.max(0, actualTokens);
        BigDecimal cost = nonNegative(actualCostCny);
        jdbcClient.sql("""
                        update agent_cost_reservation set status = 'SETTLED', actual_tokens = :tokens,
                            actual_cost_cny = :cost, settled_at = :settledAt where run_id = :runId
                        """)
                .param("tokens", tokens).param("cost", cost)
                .param("settledAt", timestamp(settledAt)).param("runId", runId).update();
        upsertDaily(row, tokens, cost, settledAt);
        upsertMonthly(row, tokens, cost, settledAt);
        ledger(row, "SETTLE", tokens - row.reservedTokens(),
                cost.subtract(row.reservedCost()), "ACTUAL_USAGE", settledAt);
    }

    @Override
    @Transactional
    public void release(UUID runId, String reason, Instant releasedAt) {
        ReservationRow row = lockReservation(runId);
        if (!"RESERVED".equals(row.status())) return;
        jdbcClient.sql("""
                        update agent_cost_reservation set status = 'RELEASED', reason = :reason,
                            settled_at = :releasedAt where run_id = :runId
                        """)
                .param("reason", reason).param("releasedAt", timestamp(releasedAt))
                .param("runId", runId).update();
        ledger(row, "RELEASE", -row.reservedTokens(), row.reservedCost().negate(), reason, releasedAt);
    }

    @Override
    public Overview overview(UUID workspaceId, LocalDate day, YearMonth month, int ledgerLimit) {
        Policy policy = policy(workspaceId, false);
        Usage usage = usage(workspaceId, day, month);
        List<LedgerEntry> entries = jdbcClient.sql("""
                        select id, run_id, user_id, entry_type, token_delta,
                               cost_delta_cny, reason, created_at
                        from agent_cost_ledger where workspace_id = :workspaceId
                        order by created_at desc limit :limit
                        """)
                .param("workspaceId", workspaceId).param("limit", Math.max(1, Math.min(200, ledgerLimit)))
                .query((rs, rowNum) -> new LedgerEntry(
                        rs.getObject("id", UUID.class), rs.getObject("run_id", UUID.class),
                        rs.getObject("user_id", UUID.class), rs.getString("entry_type"),
                        rs.getLong("token_delta"), rs.getBigDecimal("cost_delta_cny"),
                        rs.getString("reason"), rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
        return new Overview(policy, usage, List.copyOf(entries));
    }

    @Override
    public Policy updatePolicy(UUID workspaceId, UUID updatedBy, PolicyUpdate update, Instant updatedAt) {
        int updated = jdbcClient.sql("""
                        update agent_cost_policy set enabled = :enabled,
                            daily_token_limit = :dailyTokens, daily_cost_limit_cny = :dailyCost,
                            monthly_token_limit = :monthlyTokens, monthly_cost_limit_cny = :monthlyCost,
                            max_concurrent_runs = :maxConcurrent, warning_percent = :warningPercent,
                            hard_limit_enabled = :hardLimit, version = version + 1,
                            updated_by = :updatedBy, updated_at = :updatedAt
                        where workspace_id = :workspaceId
                        """)
                .param("enabled", update.enabled()).param("dailyTokens", update.dailyTokenLimit())
                .param("dailyCost", update.dailyCostLimitCny()).param("monthlyTokens", update.monthlyTokenLimit())
                .param("monthlyCost", update.monthlyCostLimitCny()).param("maxConcurrent", update.maxConcurrentRuns())
                .param("warningPercent", update.warningPercent()).param("hardLimit", update.hardLimitEnabled())
                .param("updatedBy", updatedBy).param("updatedAt", timestamp(updatedAt))
                .param("workspaceId", workspaceId).update();
        if (updated != 1) throw new IllegalArgumentException("Agent cost policy does not exist");
        return policy(workspaceId, false);
    }

    private Policy policy(UUID workspaceId, boolean lock) {
        String suffix = lock ? " for update" : "";
        return jdbcClient.sql("""
                        select workspace_id, enabled, daily_token_limit, daily_cost_limit_cny,
                               monthly_token_limit, monthly_cost_limit_cny, max_concurrent_runs,
                               warning_percent, hard_limit_enabled, version, updated_by, updated_at
                        from agent_cost_policy where workspace_id = :workspaceId
                        """ + suffix)
                .param("workspaceId", workspaceId)
                .query((rs, rowNum) -> new Policy(
                        rs.getObject("workspace_id", UUID.class), rs.getBoolean("enabled"),
                        rs.getLong("daily_token_limit"), rs.getBigDecimal("daily_cost_limit_cny"),
                        rs.getLong("monthly_token_limit"), rs.getBigDecimal("monthly_cost_limit_cny"),
                        rs.getInt("max_concurrent_runs"), rs.getInt("warning_percent"),
                        rs.getBoolean("hard_limit_enabled"), rs.getInt("version"),
                        rs.getObject("updated_by", UUID.class),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .optional().orElseThrow(() -> new IllegalArgumentException("Agent cost policy does not exist"));
    }

    private Usage usage(UUID workspaceId, LocalDate day, YearMonth month) {
        long dailySettledTokens = number("select coalesce(sum(used_tokens), 0) from agent_cost_usage_daily where workspace_id = :workspaceId and usage_day = :period", workspaceId, day);
        BigDecimal dailySettledCost = decimal("select coalesce(sum(used_cost_cny), 0) from agent_cost_usage_daily where workspace_id = :workspaceId and usage_day = :period", workspaceId, day);
        long monthlySettledTokens = number("select coalesce(sum(used_tokens), 0) from agent_cost_usage_monthly where workspace_id = :workspaceId and usage_month = :period", workspaceId, month.atDay(1));
        BigDecimal monthlySettledCost = decimal("select coalesce(sum(used_cost_cny), 0) from agent_cost_usage_monthly where workspace_id = :workspaceId and usage_month = :period", workspaceId, month.atDay(1));
        ReservationTotals reservedDay = reservationTotals(workspaceId, "usage_day", day);
        ReservationTotals reservedMonth = reservationTotals(workspaceId, "usage_month", month.atDay(1));
        return new Usage(dailySettledTokens + reservedDay.tokens(), dailySettledCost.add(reservedDay.cost()),
                monthlySettledTokens + reservedMonth.tokens(), monthlySettledCost.add(reservedMonth.cost()),
                reservedDay.count());
    }

    private ReservationTotals reservationTotals(UUID workspaceId, String periodColumn, LocalDate period) {
        return jdbcClient.sql("select coalesce(sum(reserved_tokens), 0) tokens, coalesce(sum(reserved_cost_cny), 0) cost, count(*) count from agent_cost_reservation where workspace_id = :workspaceId and status = 'RESERVED' and " + periodColumn + " = :period")
                .param("workspaceId", workspaceId).param("period", period)
                .query((rs, rowNum) -> new ReservationTotals(rs.getLong("tokens"),
                        rs.getBigDecimal("cost"), rs.getInt("count"))).single();
    }

    private long number(String sql, UUID workspaceId, LocalDate period) {
        return jdbcClient.sql(sql).param("workspaceId", workspaceId).param("period", period)
                .query(Long.class).single();
    }

    private BigDecimal decimal(String sql, UUID workspaceId, LocalDate period) {
        return jdbcClient.sql(sql).param("workspaceId", workspaceId).param("period", period)
                .query(BigDecimal.class).single();
    }

    private static String rejection(Policy policy, Usage usage) {
        if (usage.activeReservations() > policy.maxConcurrentRuns()) return "MAX_CONCURRENT_RUNS";
        if (usage.dailyTokens() > policy.dailyTokenLimit()) return "DAILY_TOKEN_LIMIT";
        if (usage.dailyCostCny().compareTo(policy.dailyCostLimitCny()) > 0) return "DAILY_COST_LIMIT";
        if (usage.monthlyTokens() > policy.monthlyTokenLimit()) return "MONTHLY_TOKEN_LIMIT";
        if (usage.monthlyCostCny().compareTo(policy.monthlyCostLimitCny()) > 0) return "MONTHLY_COST_LIMIT";
        return null;
    }

    private static boolean warning(Policy policy, Usage usage) {
        if (!policy.enabled()) return false;
        BigDecimal threshold = BigDecimal.valueOf(policy.warningPercent()).movePointLeft(2);
        return ratio(usage.dailyTokens(), policy.dailyTokenLimit()).compareTo(threshold) >= 0
                || ratio(usage.dailyCostCny(), policy.dailyCostLimitCny()).compareTo(threshold) >= 0
                || ratio(usage.monthlyTokens(), policy.monthlyTokenLimit()).compareTo(threshold) >= 0
                || ratio(usage.monthlyCostCny(), policy.monthlyCostLimitCny()).compareTo(threshold) >= 0;
    }

    private static BigDecimal ratio(long value, long limit) {
        return BigDecimal.valueOf(value).divide(BigDecimal.valueOf(limit), 6, java.math.RoundingMode.HALF_UP);
    }

    private static BigDecimal ratio(BigDecimal value, BigDecimal limit) {
        return value.divide(limit, 6, java.math.RoundingMode.HALF_UP);
    }

    private void ledger(ReservationRequest request, String type, long tokens, BigDecimal cost, String reason) {
        jdbcClient.sql("""
                        insert into agent_cost_ledger (id, workspace_id, user_id, run_id, entry_type,
                            token_delta, cost_delta_cny, reason, idempotency_key, created_at)
                        values (:id, :workspaceId, :userId, :runId, :type, :tokens, :cost, :reason,
                            :idempotencyKey, :createdAt) on conflict (idempotency_key) do nothing
                        """)
                .param("id", UUID.randomUUID()).param("workspaceId", request.workspaceId())
                .param("userId", request.userId()).param("runId", request.runId()).param("type", type)
                .param("tokens", tokens).param("cost", cost).param("reason", reason)
                .param("idempotencyKey", request.runId() + ":" + type)
                .param("createdAt", timestamp(request.createdAt())).update();
    }

    private void ledger(ReservationRow row, String type, long tokens, BigDecimal cost,
                        String reason, Instant createdAt) {
        jdbcClient.sql("""
                        insert into agent_cost_ledger (id, workspace_id, user_id, run_id, entry_type,
                            token_delta, cost_delta_cny, reason, idempotency_key, created_at)
                        values (:id, :workspaceId, :userId, :runId, :type, :tokens, :cost, :reason,
                            :idempotencyKey, :createdAt) on conflict (idempotency_key) do nothing
                        """)
                .param("id", UUID.randomUUID()).param("workspaceId", row.workspaceId())
                .param("userId", row.userId()).param("runId", row.runId()).param("type", type)
                .param("tokens", tokens).param("cost", cost).param("reason", reason)
                .param("idempotencyKey", row.runId() + ":" + type)
                .param("createdAt", timestamp(createdAt)).update();
    }

    private ReservationRow lockReservation(UUID runId) {
        return jdbcClient.sql("""
                        select run_id, workspace_id, user_id, usage_day, usage_month,
                               reserved_tokens, reserved_cost_cny, status
                        from agent_cost_reservation where run_id = :runId for update
                        """)
                .param("runId", runId)
                .query((rs, rowNum) -> new ReservationRow(
                        rs.getObject("run_id", UUID.class), rs.getObject("workspace_id", UUID.class),
                        rs.getObject("user_id", UUID.class), rs.getObject("usage_day", LocalDate.class),
                        rs.getObject("usage_month", LocalDate.class), rs.getLong("reserved_tokens"),
                        rs.getBigDecimal("reserved_cost_cny"), rs.getString("status")))
                .optional().orElseThrow(() -> new IllegalArgumentException("Cost reservation does not exist"));
    }

    private void upsertDaily(ReservationRow row, long tokens, BigDecimal cost, Instant at) {
        jdbcClient.sql("""
                        insert into agent_cost_usage_daily (workspace_id, user_id, usage_day,
                            used_tokens, used_cost_cny, updated_at)
                        values (:workspaceId, :userId, :period, :tokens, :cost, :at)
                        on conflict (workspace_id, user_id, usage_day) do update set
                            used_tokens = agent_cost_usage_daily.used_tokens + excluded.used_tokens,
                            used_cost_cny = agent_cost_usage_daily.used_cost_cny + excluded.used_cost_cny,
                            updated_at = excluded.updated_at
                        """)
                .param("workspaceId", row.workspaceId()).param("userId", row.userId())
                .param("period", row.usageDay()).param("tokens", tokens).param("cost", cost)
                .param("at", timestamp(at)).update();
    }

    private void upsertMonthly(ReservationRow row, long tokens, BigDecimal cost, Instant at) {
        jdbcClient.sql("""
                        insert into agent_cost_usage_monthly (workspace_id, user_id, usage_month,
                            used_tokens, used_cost_cny, updated_at)
                        values (:workspaceId, :userId, :period, :tokens, :cost, :at)
                        on conflict (workspace_id, user_id, usage_month) do update set
                            used_tokens = agent_cost_usage_monthly.used_tokens + excluded.used_tokens,
                            used_cost_cny = agent_cost_usage_monthly.used_cost_cny + excluded.used_cost_cny,
                            updated_at = excluded.updated_at
                        """)
                .param("workspaceId", row.workspaceId()).param("userId", row.userId())
                .param("period", row.usageMonth()).param("tokens", tokens).param("cost", cost)
                .param("at", timestamp(at)).update();
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value == null || value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record ReservationTotals(long tokens, BigDecimal cost, int count) { }

    private record ReservationRow(UUID runId, UUID workspaceId, UUID userId,
                                  LocalDate usageDay, LocalDate usageMonth,
                                  long reservedTokens, BigDecimal reservedCost, String status) { }
}
