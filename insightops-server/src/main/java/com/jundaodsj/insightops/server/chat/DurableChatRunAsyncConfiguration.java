package com.jundaodsj.insightops.server.chat;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration
public class DurableChatRunAsyncConfiguration {

    @Bean(name = "durableChatRunExecutor", destroyMethod = "close")
    ExecutorService durableChatRunExecutor(DurableChatRunProperties properties) {
        return Executors.newFixedThreadPool(properties.safeConcurrency(), Thread.ofVirtual()
                .name("durable-chat-run-", 0).factory());
    }

    @Bean(name = "durableChatHeartbeatExecutor", destroyMethod = "close")
    ScheduledExecutorService durableChatHeartbeatExecutor() {
        return Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual()
                .name("durable-chat-heartbeat-", 0).factory());
    }

    @Bean(name = "durableChatStreamExecutor", destroyMethod = "close")
    ExecutorService durableChatStreamExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
                .name("durable-chat-stream-", 0).factory());
    }
}
