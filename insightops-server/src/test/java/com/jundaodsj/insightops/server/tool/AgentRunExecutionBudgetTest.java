package com.jundaodsj.insightops.server.tool;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRunExecutionBudgetTest {

    @Test
    void shouldAtomicallyCapNodesAcrossConcurrentReservations() {
        AgentRunExecutionBudget budget = new AgentRunExecutionBudget(
                Duration.ofSeconds(10), 8, Duration.ofSeconds(5),
                12, 1_000, new BigDecimal("0.500000"));

        int reserved = IntStream.range(0, 40).parallel()
                .map(ignored -> budget.reserveNodes(1))
                .sum();

        assertThat(reserved).isEqualTo(12);
        assertThat(budget.nodesRemaining()).isZero();
        assertThat(budget.snapshot().usedNodes()).isEqualTo(12);
        assertThat(budget.exhaustionReason()).isEqualTo("MAX_NODES");
    }

    @Test
    void shouldStopPlanningAtTokenOrCostLimit() {
        AgentRunExecutionBudget budget = new AgentRunExecutionBudget(
                Duration.ofSeconds(10), 8, Duration.ofSeconds(5),
                12, 100, new BigDecimal("0.010000"));

        assertThat(budget.recordModelUsage(70, new BigDecimal("0.004000"))).isTrue();
        assertThat(budget.recordModelUsage(30, new BigDecimal("0.002000"))).isFalse();
        assertThat(budget.canPlan()).isFalse();
        assertThat(budget.exhaustionReason()).isEqualTo("MAX_MODEL_TOKENS");
        assertThat(budget.snapshot().estimatedCostCny()).isEqualByComparingTo("0.006000");
    }
}
