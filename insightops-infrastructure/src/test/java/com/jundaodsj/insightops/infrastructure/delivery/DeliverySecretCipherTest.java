package com.jundaodsj.insightops.infrastructure.delivery;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliverySecretCipherTest {

    @Test
    void encryptsWithRandomAuthenticatedPayloadsAndDetectsTampering() {
        var cipher = new DeliverySecretCipher("test-delivery-secret-key");
        String endpoint = "https://hooks.example.com/private-token";

        String first = cipher.encrypt(endpoint);
        String second = cipher.encrypt(endpoint);

        assertThat(first).isNotEqualTo(second).doesNotContain("private-token");
        assertThat(cipher.decrypt(first)).isEqualTo(endpoint);
        String tampered = first.substring(0, first.length() - 1)
                + (first.endsWith("A") ? "B" : "A");
        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decrypt delivery endpoint");
    }

    @Test
    void rejectsShortKeys() {
        assertThatThrownBy(() -> new DeliverySecretCipher("too-short"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 16");
    }
}
