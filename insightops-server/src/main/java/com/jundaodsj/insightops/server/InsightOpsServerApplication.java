package com.jundaodsj.insightops.server;

import org.springframework.boot.SpringApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.jundaodsj.insightops")
public class InsightOpsServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(InsightOpsServerApplication.class, args);
    }
}
