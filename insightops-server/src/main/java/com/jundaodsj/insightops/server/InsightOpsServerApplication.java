package com.jundaodsj.insightops.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.jundaodsj.insightops")
public class InsightOpsServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(InsightOpsServerApplication.class, args);
    }
}
