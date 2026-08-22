package com.jundaodsj.insightops.server.evaluation;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class AgentEvaluationAsyncConfiguration {

    @Bean(name = "agentEvaluationExecutor", destroyMethod = "close")
    ExecutorService agentEvaluationExecutor() {
        return Executors.newSingleThreadExecutor(Thread.ofVirtual()
                .name("agent-evaluation-", 0).factory());
    }
}
