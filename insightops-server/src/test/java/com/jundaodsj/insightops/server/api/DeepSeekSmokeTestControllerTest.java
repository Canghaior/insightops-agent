package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.model.application.ChatModelGateway;
import com.jundaodsj.insightops.model.application.ChatModelResponse;
import com.jundaodsj.insightops.model.application.ModelUsage;
import com.jundaodsj.insightops.server.chat.StructuredReleaseIntentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DeepSeekSmokeTestControllerTest {

    @Test
    void shouldRunOnlyTheFixedLowTokenPrompt() {
        ChatModelGateway gateway = request -> {
            assertThat(request.userPrompt()).contains("DEEPSEEK_SMOKE_OK");
            assertThat(request.maxOutputTokens()).isEqualTo(32);
            return new ChatModelResponse(
                    "DEEPSEEK_SMOKE_OK", "deepseek", "deepseek-v4-flash",
                    new ModelUsage(10, 5, 15, null, null), Duration.ofMillis(250));
        };
        DeepSeekSmokeTestController controller = new DeepSeekSmokeTestController(
                gateway,
                new StructuredReleaseIntentService(gateway, new ObjectMapper()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE, "trace-smoke");

        ApiResponse<DeepSeekSmokeTestController.SmokeTestResult> response = controller.run(request);

        assertThat(response.traceId()).isEqualTo("trace-smoke");
        assertThat(response.data().passed()).isTrue();
        assertThat(response.toString()).doesNotContain("api-key", "sk-");
    }

    @Test
    void shouldExposeStrictStructuredOutputSmokeTest() {
        ChatModelGateway gateway = request -> new ChatModelResponse(
                "{\"project\":\"spring-ai\",\"intent\":\"release_list\",\"timeWindowDays\":30}",
                "deepseek", "deepseek-v4-flash",
                new ModelUsage(20, 10, 30, null, null), Duration.ofMillis(100));
        DeepSeekSmokeTestController controller = new DeepSeekSmokeTestController(
                gateway,
                new StructuredReleaseIntentService(gateway, new ObjectMapper()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE, "trace-json");

        var response = controller.structuredOutput(request);

        assertThat(response.data().intent().project()).isEqualTo("spring-ai");
        assertThat(response.data().attempts()).isEqualTo(1);
    }
}
