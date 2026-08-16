package com.jundaodsj.insightops.server.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.model.application.ChatModelResponse;
import com.jundaodsj.insightops.model.application.ModelUsage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuredReleaseIntentServiceTest {

    @Test
    void shouldParseStrictJsonWithoutMarkdown() {
        StructuredReleaseIntentService service = service(() -> """
                {"project":"spring-ai","intent":"release_list","timeWindowDays":30}
                """);

        var result = service.generate();

        assertThat(result.intent().project()).isEqualTo("spring-ai");
        assertThat(result.intent().intent()).isEqualTo("release_list");
        assertThat(result.intent().timeWindowDays()).isEqualTo(30);
        assertThat(result.attempts()).isEqualTo(1);
    }

    @Test
    void shouldRetryOnceAfterInvalidJson() {
        AtomicInteger attempts = new AtomicInteger();
        StructuredReleaseIntentService service = service(() -> attempts.incrementAndGet() == 1
                ? "```json\n{}\n```"
                : "{\"project\":\"spring-ai\",\"intent\":\"release_list\",\"timeWindowDays\":30}");

        assertThat(service.generate().attempts()).isEqualTo(2);
        assertThat(attempts).hasValue(2);
    }

    @Test
    void shouldRejectExtraFieldsAndNotEchoRawModelContent() {
        String unsafe = "{\"project\":\"spring-ai\",\"intent\":\"release_list\","
                + "\"timeWindowDays\":30,\"secret\":\"secret-never-echo-this\"}";
        StructuredReleaseIntentService service = service(() -> unsafe);

        assertThatThrownBy(service::generate)
                .isInstanceOf(StructuredReleaseIntentService.StructuredOutputException.class)
                .hasMessage("MODEL_JSON_SCHEMA_MISMATCH")
                .hasMessageNotContaining("secret-never-echo-this");
    }

    private static StructuredReleaseIntentService service(ContentSupplier supplier) {
        return new StructuredReleaseIntentService(
                request -> new ChatModelResponse(
                        supplier.get(), "deepseek", "deepseek-v4-flash",
                        new ModelUsage(20, 10, 30, 0L, 0L), Duration.ofMillis(50)),
                new ObjectMapper());
    }

    @FunctionalInterface
    private interface ContentSupplier {
        String get();
    }
}
