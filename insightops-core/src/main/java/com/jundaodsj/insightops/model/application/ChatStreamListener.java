package com.jundaodsj.insightops.model.application;

public interface ChatStreamListener {

    void onEvent(ChatStreamEvent event);

    void onError(ModelCallException exception);
}
