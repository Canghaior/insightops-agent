package com.jundaodsj.insightops.server.auth;

import com.jundaodsj.insightops.infrastructure.identity.IdentityRepository;
import com.jundaodsj.insightops.infrastructure.identity.IdentitySecretCipher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class TotpService {
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private final IdentityRepository repository;
    private final IdentitySecretCipher cipher;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    @Autowired
    public TotpService(IdentityRepository repository, IdentitySecretCipher cipher) {
        this(repository, cipher, Clock.systemUTC());
    }

    TotpService(IdentityRepository repository, IdentitySecretCipher cipher, Clock clock) {
        this.repository = repository;
        this.cipher = cipher;
        this.clock = clock;
    }

    public Setup begin(UUID userId, String accountLabel) {
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        String secret = encodeBase32(bytes);
        repository.savePendingMfa(userId, cipher.encrypt(secret), clock.instant());
        String label = URLEncoder.encode("InsightOps:" + accountLabel, StandardCharsets.UTF_8);
        String uri = "otpauth://totp/" + label + "?secret=" + secret
                + "&issuer=InsightOps&algorithm=SHA1&digits=6&period=30";
        return new Setup(secret, uri);
    }

    public List<String> confirm(UUID userId, String code) {
        IdentityRepository.MfaRecord record = repository.findMfa(userId)
                .filter(value -> !value.enabled()).orElseThrow(() -> new InvalidMfaCodeException("MFA setup is not pending"));
        Instant now = clock.instant();
        Long acceptedStep = matchingStep(cipher.decrypt(record.secretCiphertext()), code, now);
        if (acceptedStep == null) {
            throw new InvalidMfaCodeException("The authenticator code is invalid");
        }
        List<String> raw = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            String codeValue = recoveryCode();
            raw.add(codeValue);
            hashes.add(hashRecovery(codeValue));
        }
        repository.enableMfa(userId, hashes, acceptedStep, now);
        return List.copyOf(raw);
    }

    public boolean enabled(UUID userId) {
        return repository.findMfa(userId).map(IdentityRepository.MfaRecord::enabled).orElse(false);
    }

    public int unusedRecoveryCodes(UUID userId) {
        return repository.unusedRecoveryCodes(userId);
    }

    public boolean verify(UUID userId, String code) {
        if (code == null || code.isBlank()) return false;
        IdentityRepository.MfaRecord record = repository.findMfa(userId)
                .filter(IdentityRepository.MfaRecord::enabled).orElse(null);
        if (record == null) return true;
        String normalized = code.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
        if (normalized.matches("[0-9]{6}")) {
            Instant now = clock.instant();
            Long acceptedStep = matchingStep(cipher.decrypt(record.secretCiphertext()), normalized, now);
            return acceptedStep != null && repository.claimTotpStep(userId, acceptedStep, now);
        }
        return normalized.matches("[A-Z2-9]{12}")
                && repository.consumeRecoveryCode(userId, hashRecovery(normalized), clock.instant());
    }

    public void disable(UUID userId) {
        repository.disableMfa(userId);
    }

    static boolean verifyTotp(String secret, String code, Instant now) {
        return matchingStep(secret, code, now) != null;
    }

    private static Long matchingStep(String secret, String code, Instant now) {
        if (code == null || !code.matches("[0-9]{6}")) return null;
        long counter = now.getEpochSecond() / 30;
        for (long candidate = counter - 1; candidate <= counter + 1; candidate++) {
            if (MessageDigest.isEqual(generate(secret, candidate).getBytes(StandardCharsets.US_ASCII),
                    code.getBytes(StandardCharsets.US_ASCII))) return candidate;
        }
        return null;
    }

    static String generate(String secret, long counter) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decodeBase32(secret), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            return String.format(Locale.ROOT, "%06d", binary % 1_000_000);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to calculate TOTP", exception);
        }
    }

    static String encodeBase32(byte[] bytes) {
        StringBuilder result = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 0xff);
            bits += 8;
            while (bits >= 5) {
                result.append(BASE32[(buffer >> (bits - 5)) & 31]);
                bits -= 5;
            }
        }
        if (bits > 0) result.append(BASE32[(buffer << (5 - bits)) & 31]);
        return result.toString();
    }

    static byte[] decodeBase32(String value) {
        String normalized = value.replace("=", "").toUpperCase(Locale.ROOT);
        ByteBuffer output = ByteBuffer.allocate(normalized.length() * 5 / 8 + 1);
        int buffer = 0;
        int bits = 0;
        for (char character : normalized.toCharArray()) {
            int digit = character >= 'A' && character <= 'Z'
                    ? character - 'A' : character >= '2' && character <= '7' ? character - '2' + 26 : -1;
            if (digit < 0) throw new IllegalArgumentException("Invalid Base32 value");
            buffer = (buffer << 5) | digit;
            bits += 5;
            if (bits >= 8) {
                output.put((byte) ((buffer >> (bits - 8)) & 0xff));
                bits -= 8;
            }
        }
        byte[] bytes = new byte[output.position()];
        output.flip();
        output.get(bytes);
        return bytes;
    }

    private String recoveryCode() {
        byte[] bytes = new byte[8];
        random.nextBytes(bytes);
        String compact = encodeBase32(bytes).substring(0, 12);
        return compact.substring(0, 4) + '-' + compact.substring(4, 8) + '-' + compact.substring(8);
    }

    static String hashRecovery(String code) {
        try {
            String normalized = code.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record Setup(String secret, String otpauthUri) { }

    public static class InvalidMfaCodeException extends RuntimeException {
        public InvalidMfaCodeException(String message) { super(message); }
    }
}
