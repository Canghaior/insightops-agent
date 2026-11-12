package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.infrastructure.config.ModelReadiness;
import com.jundaodsj.insightops.server.chat.DurableChatRunProperties;
import com.jundaodsj.insightops.server.chat.KnowledgeRagProperties;
import com.jundaodsj.insightops.server.knowledge.KnowledgeEmbeddingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class SystemStatusControllerTest {

    @Test
    void shouldExposeModelReadinessWithoutLeakingApiKey() {
        ModelReadiness model = new ModelReadiness(true, true, "deepseek", "deepseek-v4-flash");
        SystemStatusController controller = controller(model, true, false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE, "trace-test");

        ApiResponse<SystemStatusController.SystemStatus> response = controller.status(request);

        assertThat(response.traceId()).isEqualTo("trace-test");
        assertThat(response.data().model().ready()).isTrue();
        assertThat(response.data().status()).isEqualTo("CONFIGURED");
        assertThat(response.data().model().verified()).isFalse();
        assertThat(response.data().model().state()).isEqualTo("CONFIGURED_UNVERIFIED");
        assertThat(response.data().verification()).isEqualTo("CONFIGURATION_ONLY");
        assertThat(response.data().capabilities()).anySatisfy(capability -> {
            assertThat(capability.id()).isEqualTo("retrieval");
            assertThat(capability.status()).isEqualTo("DEGRADED");
        });
        assertThat(response.data().capabilities().stream()
                .filter(capability -> capability.id().equals("collection") || capability.id().equals("delivery")))
                .allSatisfy(capability -> assertThat(capability.status()).isEqualTo("UNKNOWN"));
        assertThat(response.toString()).doesNotContain("api-key", "sk-");
    }

    @Test
    void disabledOrIncompleteConfigurationMustNotBeReportedAsOperational() {
        var disabled = controller(new ModelReadiness(false, true, "deepseek", "model"), false, true)
                .status(new MockHttpServletRequest()).data();
        assertThat(disabled.status()).isEqualTo("LIMITED");
        assertThat(disabled.model().state()).isEqualTo("DISABLED");
        assertThat(disabled.capabilities()).anySatisfy(capability -> {
            assertThat(capability.id()).isEqualTo("retrieval");
            assertThat(capability.status()).isEqualTo("DISABLED");
        });

        var missing = controller(new ModelReadiness(true, false, "deepseek", "model"), true, true)
                .status(new MockHttpServletRequest()).data();
        assertThat(missing.model().state()).isEqualTo("MISSING_CONFIGURATION");
        assertThat(missing.model().ready()).isFalse();
        assertThat(missing.model().verified()).isFalse();
    }

    private SystemStatusController controller(ModelReadiness model, boolean ragEnabled, boolean embeddingEnabled) {
        var rag = new KnowledgeRagProperties();
        rag.setEnabled(ragEnabled);
        var embedding = new KnowledgeEmbeddingProperties();
        embedding.setEnabled(embeddingEnabled);
        return new SystemStatusController(model,
                new SystemCapabilityService(model, rag, embedding, new DurableChatRunProperties()));
    }
}
