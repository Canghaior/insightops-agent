package com.jundaodsj.insightops.agent.domain;

public enum AgentRunStatus {
    CREATED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
