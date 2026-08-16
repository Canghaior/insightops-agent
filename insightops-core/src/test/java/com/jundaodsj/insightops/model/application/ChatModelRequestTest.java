package com.jundaodsj.insightops.model.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatModelRequestTest {

    @Test
    void shouldNormalizePrompts() {
        ChatModelRequest request = new ChatModelRequest(" system ", " question ", 0.2, 32);

        assertThat(request.systemPrompt()).isEqualTo("system");
        assertThat(request.userPrompt()).isEqualTo("question");
    }

    @Test
    void shouldRejectInvalidLimits() {
        assertThatThrownBy(() -> new ChatModelRequest("", "", 0.2, 32))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatModelRequest("", "ok", -0.1, 32))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatModelRequest("", "ok", 0.2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
