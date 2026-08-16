package com.jundaodsj.insightops.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.jundaodsj.insightops")
public class InsightOpsWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(InsightOpsWorkerApplication.class, args);
    }
}
