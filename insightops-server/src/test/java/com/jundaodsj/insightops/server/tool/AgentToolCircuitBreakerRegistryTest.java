package com.jundaodsj.insightops.server.tool;

import com.jundaodsj.insightops.tool.application.registry.AgentToolNames;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolCircuitBreakerRegistryTest {

    @Test
    void opensAfterThresholdAndRejectsWithoutCallingUpstream() {
        AgentToolResilienceProperties properties = new AgentToolResilienceProperties();
        properties.setCircuitWindowSize(2);
        properties.setCircuitMinimumCalls(2);
        properties.setCircuitFailureRatePercent(50);
        properties.setCircuitOpenSeconds(30);
        AgentToolOperationalMetrics metrics =
                new AgentToolOperationalMetrics(new SimpleMeterRegistry());
        AgentToolCircuitBreakerRegistry registry =
                new AgentToolCircuitBreakerRegistry(properties, metrics);

        AgentToolCircuitBreakerRegistry.Permit first =
                registry.acquire(AgentToolNames.GITHUB_RELEASE_LIST);
        AgentToolCircuitBreakerRegistry.Permit second =
                registry.acquire(AgentToolNames.GITHUB_RELEASE_LIST);
        assertThat(first.allowed()).isTrue();
        assertThat(second.allowed()).isTrue();
        first.failure();
        second.failure();

        AgentToolCircuitBreakerRegistry.Permit rejected =
                registry.acquire(AgentToolNames.GITHUB_RELEASE_LIST);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.group()).isEqualTo("github");
    }

    @Test
    void permanentErrorsDoNotOpenCircuit() {
        AgentToolResilienceProperties properties = new AgentToolResilienceProperties();
        properties.setCircuitWindowSize(2);
        properties.setCircuitMinimumCalls(2);
        AgentToolOperationalMetrics metrics =
                new AgentToolOperationalMetrics(new SimpleMeterRegistry());
        AgentToolCircuitBreakerRegistry registry =
                new AgentToolCircuitBreakerRegistry(properties, metrics);

        for (int index = 0; index < 5; index++) {
            AgentToolCircuitBreakerRegistry.Permit permit =
                    registry.acquire(AgentToolNames.KNOWLEDGE_HYBRID_SEARCH);
            assertThat(permit.allowed()).isTrue();
            permit.ignored();
        }
    }
}
