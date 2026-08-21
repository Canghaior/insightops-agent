package com.jundaodsj.insightops.tool.domain;

public enum ToolCallStatus {
    REQUESTED,
    RUNNING,
    WAITING_APPROVAL,
    REJECTED,
    COMPENSATED,
    SUCCEEDED,
    FAILED,
    TIMED_OUT;

    public boolean isTerminal() {
        return this == REJECTED || this == COMPENSATED
                || this == SUCCEEDED || this == FAILED || this == TIMED_OUT;
    }
}
