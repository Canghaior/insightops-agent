package com.jundaodsj.insightops.tool.domain;

public enum ToolCallStatus {
    REQUESTED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == TIMED_OUT;
    }
}
