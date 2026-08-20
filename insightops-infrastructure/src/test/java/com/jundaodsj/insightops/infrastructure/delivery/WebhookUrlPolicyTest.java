package com.jundaodsj.insightops.infrastructure.delivery;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookUrlPolicyTest {

    @Test
    void acceptsAndMasksPublicHttpsSyntax() {
        var endpoint = WebhookUrlPolicy.syntax("https://hooks.example.com/v1/private-token?key=secret");

        assertThat(endpoint.getHost()).isEqualTo("hooks.example.com");
        assertThat(WebhookUrlPolicy.masked(endpoint)).isEqualTo("https://hooks.example.com/***");
    }

    @Test
    void rejectsInsecureOrLocalEndpointSyntax() {
        assertThatThrownBy(() -> WebhookUrlPolicy.syntax("http://hooks.example.com/a"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookUrlPolicy.syntax("https://localhost/a"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookUrlPolicy.syntax("https://user:pass@hooks.example.com/a"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookUrlPolicy.syntax("https://hooks.example.com:8443/a"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookUrlPolicy.resolvedPublic("https://127.0.0.1/a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-public");
    }
}
