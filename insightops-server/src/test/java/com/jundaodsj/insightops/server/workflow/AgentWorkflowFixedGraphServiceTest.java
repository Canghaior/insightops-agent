package com.jundaodsj.insightops.server.workflow;

import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentWorkflowFixedGraphServiceTest {

    @Test
    void sortsACopyWhenWaveResultsAreImmutable() {
        List<Integer> immutableWaveResults = List.of(3, 1, 2);

        List<Integer> ordered = AgentWorkflowFixedGraphService.sortedCopy(
                immutableWaveResults, Comparator.naturalOrder());

        assertThat(ordered).containsExactly(1, 2, 3);
        assertThat(immutableWaveResults).containsExactly(3, 1, 2);
    }
}
