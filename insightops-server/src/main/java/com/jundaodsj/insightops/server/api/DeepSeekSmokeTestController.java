package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.model.application.ChatModelGateway;
import com.jundaodsj.insightops.model.application.ChatModelRequest;
import com.jundaodsj.insightops.model.application.ChatModelResponse;
import com.jundaodsj.insightops.model.application.ModelUsage;
import com.jundaodsj.insightops.server.chat.StructuredReleaseIntentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/model")
@ConditionalOnProperty(
        prefix = "insightops.model.deepseek",
        name = {"enabled", "smoke-test-enabled"},
        havingValue = "true")
public class DeepSeekSmokeTestController {

    private static final String EXPECTED_RESPONSE = "DEEPSEEK_SMOKE_OK";

    private final ChatModelGateway chatModelGateway;
    private final StructuredReleaseIntentService structuredReleaseIntentService;

    public DeepSeekSmokeTestController(
            ChatModelGateway chatModelGateway,
            StructuredReleaseIntentService structuredReleaseIntentService) {
        this.chatModelGateway = chatModelGateway;
        this.structuredReleaseIntentService = structuredReleaseIntentService;
    }

    @PostMapping("/structured-output-test")
    public ApiResponse<StructuredReleaseIntentService.StructuredResult> structuredOutput(
            HttpServletRequest request) {
        return new ApiResponse<>(
                (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE),
                structuredReleaseIntentService.generate());
    }

    @PostMapping("/smoke-test")
    public ApiResponse<SmokeTestResult> run(HttpServletRequest request) {
        ChatModelResponse response = chatModelGateway.generate(new ChatModelRequest(
                "You are a deterministic API smoke test. Follow the user instruction exactly.",
                "Reply with exactly DEEPSEEK_SMOKE_OK and nothing else.",
                0.0,
                32));
        String traceId = (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return new ApiResponse<>(traceId, new SmokeTestResult(
                EXPECTED_RESPONSE.equals(response.content()),
                response.content(),
                response.provider(),
                response.model(),
                response.usage(),
                response.duration().toMillis()));
    }

    public record SmokeTestResult(
            boolean passed,
            String content,
            String provider,
            String model,
            ModelUsage usage,
            long durationMs) {
    }
}
