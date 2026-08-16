package com.jundaodsj.insightops.job.domain;

public enum JobTaskStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    RETRY_WAIT,
    FAILED,
    DEAD_LETTER;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == DEAD_LETTER;
    }
}
