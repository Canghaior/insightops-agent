package com.jundaodsj.insightops.model.application;

public interface StreamingChatModelGateway {

    ChatStreamSession stream(ChatModelRequest request, ChatStreamListener listener);
}
