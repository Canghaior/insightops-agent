package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.infrastructure.config.ModelReadiness;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/system")
public class SystemStatusController {

    private final ModelReadiness modelReadiness;

    public SystemStatusController(ModelReadiness modelReadiness) {
        this.modelReadiness = modelReadiness;
    }

    @GetMapping("/status")
    public ApiResponse<SystemStatus> status(HttpServletRequest request) {
        String traceId = (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        SystemStatus status = new SystemStatus(
                "insightops-server",
                "UP",
                Instant.now(),
                new ModelStatus(
                        modelReadiness.provider(),
                        modelReadiness.model(),
                        modelReadiness.ready()));
        return new ApiResponse<>(traceId, status);
    }

    public record SystemStatus(String service, String status, Instant timestamp, ModelStatus model) {
    }

    public record ModelStatus(String provider, String model, boolean ready) {
    }
}
