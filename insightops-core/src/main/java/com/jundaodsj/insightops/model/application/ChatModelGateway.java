package com.jundaodsj.insightops.model.application;

public interface ChatModelGateway {

    ChatModelResponse generate(ChatModelRequest request);
}
