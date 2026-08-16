package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.infrastructure.config.ModelReadiness;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class SystemStatusControllerTest {

    @Test
    void shouldExposeModelReadinessWithoutLeakingApiKey() {
        SystemStatusController controller = new SystemStatusController(
                new ModelReadiness(true, true, "deepseek", "deepseek-v4-flash"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE, "trace-test");

        ApiResponse<SystemStatusController.SystemStatus> response = controller.status(request);

        assertThat(response.traceId()).isEqualTo("trace-test");
        assertThat(response.data().model().ready()).isTrue();
        assertThat(response.toString()).doesNotContain("api-key", "sk-");
    }
}
