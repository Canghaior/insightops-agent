package com.jundaodsj.insightops.agent.domain;

import com.jundaodsj.insightops.foundation.error.InvalidStateTransitionException;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class AgentRunStateMachine {

    private static final Map<AgentRunStatus, Set<AgentRunStatus>> TRANSITIONS = transitions();

    private AgentRunStateMachine() {
    }

    public static AgentRunStatus transition(AgentRunStatus current, AgentRunStatus target) {
        if (!TRANSITIONS.get(current).contains(target)) {
            throw new InvalidStateTransitionException(current, target);
        }
        return target;
    }

    private static Map<AgentRunStatus, Set<AgentRunStatus>> transitions() {
        EnumMap<AgentRunStatus, Set<AgentRunStatus>> transitions = new EnumMap<>(AgentRunStatus.class);
        transitions.put(AgentRunStatus.CREATED, EnumSet.of(AgentRunStatus.RUNNING, AgentRunStatus.CANCELLED));
        transitions.put(AgentRunStatus.RUNNING,
                EnumSet.of(AgentRunStatus.SUCCEEDED, AgentRunStatus.FAILED, AgentRunStatus.CANCELLED));
        transitions.put(AgentRunStatus.SUCCEEDED, EnumSet.noneOf(AgentRunStatus.class));
        transitions.put(AgentRunStatus.FAILED, EnumSet.noneOf(AgentRunStatus.class));
        transitions.put(AgentRunStatus.CANCELLED, EnumSet.noneOf(AgentRunStatus.class));
        return Map.copyOf(transitions);
    }
}
