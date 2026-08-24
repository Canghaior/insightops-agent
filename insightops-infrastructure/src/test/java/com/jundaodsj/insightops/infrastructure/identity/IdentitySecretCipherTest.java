package com.jundaodsj.insightops.infrastructure.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentitySecretCipherTest {
    @Test
    void encryptsWithRandomAesGcmNonceAndRejectsTampering() {
        IdentitySecretCipher cipher = new IdentitySecretCipher("test-identity-key-with-32-characters");
        String first = cipher.encrypt("sensitive-token");
        String second = cipher.encrypt("sensitive-token");

        assertThat(first).isNotEqualTo(second).doesNotContain("sensitive-token");
        assertThat(cipher.decrypt(first)).isEqualTo("sensitive-token");
        byte[] payload = java.util.Base64.getUrlDecoder().decode(first);
        payload[payload.length - 1] ^= 1;
        String tampered = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }
}
