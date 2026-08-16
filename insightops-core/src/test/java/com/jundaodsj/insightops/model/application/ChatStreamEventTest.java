package com.jundaodsj.insightops.model.application;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatStreamEventTest {

    @Test
    void shouldCreateDeltaAndCompletionEvents() {
        assertThat(ChatStreamEvent.delta("Spring").type()).isEqualTo(ChatStreamEventType.CONTENT_DELTA);

        ChatStreamEvent completed = ChatStreamEvent.completed(
                "deepseek",
                "deepseek-v4-flash",
                new ModelUsage(10, 5, 15, 0L, 0L),
                Duration.ofSeconds(1),
                Duration.ofMillis(200));

        assertThat(completed.type()).isEqualTo(ChatStreamEventType.COMPLETED);
        assertThat(completed.usage().totalTokens()).isEqualTo(15);
    }

    @Test
    void shouldRejectEmptyDelta() {
        assertThatThrownBy(() -> ChatStreamEvent.delta(""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
