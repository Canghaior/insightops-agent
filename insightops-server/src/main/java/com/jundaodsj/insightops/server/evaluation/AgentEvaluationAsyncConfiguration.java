package com.jundaodsj.insightops.server.evaluation;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration
public class AgentEvaluationAsyncConfiguration {

    @Bean(name = "agentEvaluationExecutor", destroyMethod = "close")
    ExecutorService agentEvaluationExecutor(AgentEvaluationQueueProperties properties) {
        return Executors.newFixedThreadPool(properties.safeConcurrency(), Thread.ofVirtual()
                .name("agent-evaluation-", 0).factory());
    }

    @Bean(name = "agentEvaluationHeartbeatExecutor", destroyMethod = "close")
    ScheduledExecutorService agentEvaluationHeartbeatExecutor() {
        return Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual()
                .name("agent-evaluation-heartbeat-", 0).factory());
    }
}
