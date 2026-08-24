package com.jundaodsj.insightops.infrastructure.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class IdentitySecretCipher {
    private static final byte VERSION = 1;
    private static final int NONCE_BYTES = 12;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public IdentitySecretCipher(
            @Value("${insightops.identity.encryption-key:insightops-dev-identity-key}") String secret) {
        if (secret == null || secret.length() < 16) {
            throw new IllegalStateException("Identity encryption key must contain at least 16 characters");
        }
        try {
            this.key = new SecretKeySpec(MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to initialize identity encryption", exception);
        }
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("Identity secret must not be blank");
        }
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    ByteBuffer.allocate(1 + nonce.length + encrypted.length)
                            .put(VERSION).put(nonce).put(encrypted).array());
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to encrypt identity secret", exception);
        }
    }

    public String decrypt(String ciphertext) {
        try {
            byte[] payload = Base64.getUrlDecoder().decode(ciphertext);
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            if (buffer.remaining() <= NONCE_BYTES + 1 || buffer.get() != VERSION) {
                throw new IllegalArgumentException("Unsupported identity ciphertext version");
            }
            byte[] nonce = new byte[NONCE_BYTES];
            buffer.get(nonce);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to decrypt identity secret", exception);
        }
    }
}
