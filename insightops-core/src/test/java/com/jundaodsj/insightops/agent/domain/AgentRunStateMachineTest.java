package com.jundaodsj.insightops.agent.domain;

import com.jundaodsj.insightops.foundation.error.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRunStateMachineTest {

    @Test
    void shouldCompleteAValidRun() {
        AgentRunStatus running = AgentRunStateMachine.transition(AgentRunStatus.CREATED, AgentRunStatus.RUNNING);
        AgentRunStatus succeeded = AgentRunStateMachine.transition(running, AgentRunStatus.SUCCEEDED);

        assertThat(succeeded).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(succeeded.isTerminal()).isTrue();
    }

    @Test
    void shouldRejectTransitionFromTerminalState() {
        assertThatThrownBy(() -> AgentRunStateMachine.transition(
                AgentRunStatus.SUCCEEDED, AgentRunStatus.RUNNING))
                .isInstanceOf(InvalidStateTransitionException.class);
    }
}
