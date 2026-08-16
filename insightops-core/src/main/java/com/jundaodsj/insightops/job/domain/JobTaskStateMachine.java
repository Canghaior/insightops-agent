package com.jundaodsj.insightops.job.domain;

import com.jundaodsj.insightops.foundation.error.InvalidStateTransitionException;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class JobTaskStateMachine {

    private static final Map<JobTaskStatus, Set<JobTaskStatus>> TRANSITIONS = transitions();

    private JobTaskStateMachine() {
    }

    public static JobTaskStatus transition(JobTaskStatus current, JobTaskStatus target) {
        if (!TRANSITIONS.get(current).contains(target)) {
            throw new InvalidStateTransitionException(current, target);
        }
        return target;
    }

    private static Map<JobTaskStatus, Set<JobTaskStatus>> transitions() {
        EnumMap<JobTaskStatus, Set<JobTaskStatus>> transitions = new EnumMap<>(JobTaskStatus.class);
        transitions.put(JobTaskStatus.PENDING, EnumSet.of(JobTaskStatus.RUNNING));
        transitions.put(JobTaskStatus.RUNNING,
                EnumSet.of(JobTaskStatus.SUCCEEDED, JobTaskStatus.RETRY_WAIT, JobTaskStatus.FAILED));
        transitions.put(JobTaskStatus.RETRY_WAIT,
                EnumSet.of(JobTaskStatus.PENDING, JobTaskStatus.DEAD_LETTER));
        transitions.put(JobTaskStatus.SUCCEEDED, EnumSet.noneOf(JobTaskStatus.class));
        transitions.put(JobTaskStatus.FAILED, EnumSet.noneOf(JobTaskStatus.class));
        transitions.put(JobTaskStatus.DEAD_LETTER, EnumSet.noneOf(JobTaskStatus.class));
        return Map.copyOf(transitions);
    }
}
