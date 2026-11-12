package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.infrastructure.config.ModelReadiness;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/system")
public class SystemStatusController {

    private final ModelReadiness modelReadiness;
    private final SystemCapabilityService capabilities;

    public SystemStatusController(ModelReadiness modelReadiness, SystemCapabilityService capabilities) {
        this.modelReadiness = modelReadiness;
        this.capabilities = capabilities;
    }

    @GetMapping("/status")
    public ApiResponse<SystemStatus> status(HttpServletRequest request) {
        String traceId = (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        SystemStatus status = new SystemStatus(
                "insightops-server",
                modelReadiness.ready() ? "CONFIGURED" : "LIMITED",
                Instant.now(),
                new ModelStatus(
                        modelReadiness.provider(),
                        modelReadiness.model(),
                        modelReadiness.ready(), modelReadiness.configurationState(), false),
                "CONFIGURATION_ONLY", capabilities.snapshot());
        return new ApiResponse<>(traceId, status);
    }

    public record SystemStatus(String service, String status, Instant timestamp, ModelStatus model,
                               String verification, List<SystemCapabilityService.Capability> capabilities) {
    }

    public record ModelStatus(String provider, String model, boolean ready, String state, boolean verified) {
    }
}
