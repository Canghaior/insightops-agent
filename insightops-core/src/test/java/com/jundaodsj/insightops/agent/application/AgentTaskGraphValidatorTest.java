package com.jundaodsj.insightops.agent.application;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentTaskGraphValidatorTest {

    @Test
    void shouldAcceptLayeredAcyclicGraph() {
        UUID root = UUID.randomUUID();
        UUID sibling = UUID.randomUUID();
        UUID dependent = UUID.randomUUID();

        assertThatCode(() -> AgentTaskGraphValidator.validate(List.of(
                new AgentTaskGraphValidator.Node(root, List.of()),
                new AgentTaskGraphValidator.Node(sibling, List.of()),
                new AgentTaskGraphValidator.Node(dependent, List.of(root, sibling))), 3))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectCycleAndMissingDependency() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertThatThrownBy(() -> AgentTaskGraphValidator.validate(List.of(
                new AgentTaskGraphValidator.Node(first, List.of(second)),
                new AgentTaskGraphValidator.Node(second, List.of(first))), 4))
                .isInstanceOf(AgentTaskGraphValidator.GraphValidationException.class)
                .hasMessage("PLAN_DEPENDENCY_CYCLE");

        assertThatThrownBy(() -> AgentTaskGraphValidator.validate(List.of(
                new AgentTaskGraphValidator.Node(first, List.of(UUID.randomUUID()))), 4))
                .isInstanceOf(AgentTaskGraphValidator.GraphValidationException.class)
                .hasMessage("PLAN_DEPENDENCY_MISSING");
    }
}
