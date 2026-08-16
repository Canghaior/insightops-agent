package com.jundaodsj.insightops.worker;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/internal/worker")
public class WorkerStatusController {

    @GetMapping("/status")
    public WorkerStatus status() {
        return new WorkerStatus("insightops-worker", "UP", Instant.now());
    }

    public record WorkerStatus(String service, String status, Instant timestamp) {
    }
}
