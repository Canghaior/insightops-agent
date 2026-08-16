package com.jundaodsj.insightops.model.application;

public interface ChatStreamSession {

    void cancel();

    boolean cancelled();
}
