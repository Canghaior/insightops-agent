package com.jundaodsj.insightops.server.auth;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TotpServiceTest {
    @Test
    void roundTripsBase32AndAcceptsOnlyTheExpectedTimeWindow() {
        byte[] bytes = "12345678901234567890".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        String secret = TotpService.encodeBase32(bytes);
        Instant now = Instant.parse("2026-08-25T00:00:00Z");
        String code = TotpService.generate(secret, now.getEpochSecond() / 30);

        assertThat(TotpService.decodeBase32(secret)).isEqualTo(bytes);
        assertThat(TotpService.verifyTotp(secret, code, now)).isTrue();
        assertThat(TotpService.verifyTotp(secret, code, now.plusSeconds(61))).isFalse();
        assertThat(TotpService.verifyTotp(secret, "12345", now)).isFalse();
    }

    @Test
    void recoveryHashIgnoresDisplaySeparators() {
        assertThat(TotpService.hashRecovery("ABCD-EFGH-JKLM"))
                .isEqualTo(TotpService.hashRecovery("abcdefghjklm"));
    }
}
