package com.jundaodsj.insightops.job.domain;

import com.jundaodsj.insightops.foundation.error.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobTaskStateMachineTest {

    @Test
    void shouldMoveRetryBackToPending() {
        assertThat(JobTaskStateMachine.transition(JobTaskStatus.RETRY_WAIT, JobTaskStatus.PENDING))
                .isEqualTo(JobTaskStatus.PENDING);
    }

    @Test
    void shouldRejectRetryAfterSuccess() {
        assertThatThrownBy(() -> JobTaskStateMachine.transition(
                JobTaskStatus.SUCCEEDED, JobTaskStatus.RETRY_WAIT))
                .isInstanceOf(InvalidStateTransitionException.class);
    }
}
