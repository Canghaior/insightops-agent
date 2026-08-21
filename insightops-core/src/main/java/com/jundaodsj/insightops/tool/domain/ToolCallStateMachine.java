package com.jundaodsj.insightops.tool.domain;

import com.jundaodsj.insightops.foundation.error.InvalidStateTransitionException;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class ToolCallStateMachine {

    private static final Map<ToolCallStatus, Set<ToolCallStatus>> TRANSITIONS = transitions();

    private ToolCallStateMachine() {
    }

    public static ToolCallStatus transition(ToolCallStatus current, ToolCallStatus target) {
        if (!TRANSITIONS.get(current).contains(target)) {
            throw new InvalidStateTransitionException(current, target);
        }
        return target;
    }

    private static Map<ToolCallStatus, Set<ToolCallStatus>> transitions() {
        EnumMap<ToolCallStatus, Set<ToolCallStatus>> transitions = new EnumMap<>(ToolCallStatus.class);
        transitions.put(ToolCallStatus.REQUESTED, EnumSet.of(ToolCallStatus.RUNNING, ToolCallStatus.FAILED));
        transitions.put(ToolCallStatus.RUNNING,
                EnumSet.of(ToolCallStatus.WAITING_APPROVAL, ToolCallStatus.SUCCEEDED,
                        ToolCallStatus.FAILED, ToolCallStatus.TIMED_OUT));
        transitions.put(ToolCallStatus.WAITING_APPROVAL,
                EnumSet.of(ToolCallStatus.SUCCEEDED, ToolCallStatus.REJECTED,
                        ToolCallStatus.FAILED));
        transitions.put(ToolCallStatus.REJECTED, EnumSet.noneOf(ToolCallStatus.class));
        transitions.put(ToolCallStatus.COMPENSATED, EnumSet.noneOf(ToolCallStatus.class));
        transitions.put(ToolCallStatus.SUCCEEDED, EnumSet.noneOf(ToolCallStatus.class));
        transitions.put(ToolCallStatus.FAILED, EnumSet.noneOf(ToolCallStatus.class));
        transitions.put(ToolCallStatus.TIMED_OUT, EnumSet.noneOf(ToolCallStatus.class));
        return Map.copyOf(transitions);
    }
}
