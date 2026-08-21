package com.jundaodsj.insightops.server.chat;

import com.jundaodsj.insightops.agent.application.AgentCostGovernanceStore;
import com.jundaodsj.insightops.infrastructure.config.DeepSeekPricingProperties;
import com.jundaodsj.insightops.infrastructure.model.DeepSeekCostEstimator;
import com.jundaodsj.insightops.model.application.ModelUsage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentCostGovernanceServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-22T01:00:00Z");

    @Test
    void springSelectsTheProductionConstructorWhenTheServiceHasATestClockConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AgentCostGovernanceStore.class,
                    () -> mock(AgentCostGovernanceStore.class));
            context.registerBean(AgentCostGovernanceProperties.class,
                    AgentCostGovernanceProperties::new);
            context.registerBean(DeepSeekCostEstimator.class,
                    () -> mock(DeepSeekCostEstimator.class));
            context.registerBean(AgentCostGovernanceMetrics.class,
                    () -> new AgentCostGovernanceMetrics(new SimpleMeterRegistry()));
            context.register(AgentCostGovernanceService.class);
            context.refresh();

            org.assertj.core.api.Assertions.assertThat(
                    context.getBean(AgentCostGovernanceService.class)).isNotNull();
        }
    }

    @Test
    void rejectsAWorkspaceRunWhenTheAtomicReservationIsDenied() {
        AgentCostGovernanceStore store = mock(AgentCostGovernanceStore.class);
        AgentCostGovernanceStore.Policy policy = policy();
        when(store.ensurePolicy(any(), any(), any())).thenReturn(policy);
        when(store.reserve(any())).thenReturn(new AgentCostGovernanceStore.ReservationDecision(
                false, "DAILY_TOKEN_LIMIT", null,
                new AgentCostGovernanceStore.Usage(100, BigDecimal.ONE, 100, BigDecimal.ONE, 1),
                policy, true));
        AgentCostGovernanceService service = service(store);

        assertThatThrownBy(() -> service.reserve(
                UUID.randomUUID(), policy.workspaceId(), UUID.randomUUID(), 50, BigDecimal.ONE))
                .isInstanceOf(AgentCostGovernanceService.CostQuotaException.class)
                .hasMessage("DAILY_TOKEN_LIMIT");
    }

    @Test
    void settlesActualTokensAndEstimatedCostAgainstTheReservation() {
        AgentCostGovernanceStore store = mock(AgentCostGovernanceStore.class);
        UUID runId = UUID.randomUUID();
        when(store.findByRun(runId)).thenReturn(java.util.Optional.of(
                new AgentCostGovernanceStore.Reservation(
                        UUID.randomUUID(), runId, UUID.randomUUID(), UUID.randomUUID(),
                        2_000, BigDecimal.ONE, 0, BigDecimal.ZERO, "RESERVED", null, NOW, null)));
        AgentCostGovernanceService service = service(store);

        service.settle(runId, new ModelUsage(1_000, 500, 1_500, 200L, 0L));

        verify(store).settle(
                org.mockito.ArgumentMatchers.eq(runId),
                org.mockito.ArgumentMatchers.eq(1_500L),
                org.mockito.ArgumentMatchers.argThat(cost -> cost.signum() > 0),
                org.mockito.ArgumentMatchers.eq(NOW));
    }

    @Test
    void rejectsAnInvalidPolicyBeforeWritingIt() {
        AgentCostGovernanceStore store = mock(AgentCostGovernanceStore.class);
        AgentCostGovernanceService service = service(store);
        AgentCostGovernanceStore.PolicyUpdate invalid = new AgentCostGovernanceStore.PolicyUpdate(
                true, 1_000, BigDecimal.TEN, 999, BigDecimal.ONE, 2, 80, true);

        assertThatThrownBy(() -> service.update(UUID.randomUUID(), UUID.randomUUID(), invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid agent cost policy");
    }

    private static AgentCostGovernanceService service(AgentCostGovernanceStore store) {
        return new AgentCostGovernanceService(
                store,
                new AgentCostGovernanceProperties(),
                new DeepSeekCostEstimator(new DeepSeekPricingProperties(
                        LocalDate.parse("2026-08-16"), new BigDecimal("7.2"),
                        new BigDecimal("0.0028"), new BigDecimal("0.14"),
                        new BigDecimal("0.28"))),
                new AgentCostGovernanceMetrics(new SimpleMeterRegistry()),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static AgentCostGovernanceStore.Policy policy() {
        return new AgentCostGovernanceStore.Policy(
                UUID.randomUUID(), true, 500_000, new BigDecimal("20"),
                10_000_000, new BigDecimal("300"), 5, 80, true, 1,
                null, NOW);
    }
}
