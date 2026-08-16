package com.jundaodsj.insightops.tool.domain;

import com.jundaodsj.insightops.foundation.error.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolCallStateMachineTest {

    @Test
    void shouldAllowTimeoutOnlyAfterRunning() {
        assertThat(ToolCallStateMachine.transition(ToolCallStatus.RUNNING, ToolCallStatus.TIMED_OUT))
                .isEqualTo(ToolCallStatus.TIMED_OUT);
        assertThatThrownBy(() -> ToolCallStateMachine.transition(
                ToolCallStatus.REQUESTED, ToolCallStatus.TIMED_OUT))
                .isInstanceOf(InvalidStateTransitionException.class);
    }
}
