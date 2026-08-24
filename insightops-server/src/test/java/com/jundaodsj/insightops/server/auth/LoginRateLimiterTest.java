package com.jundaodsj.insightops.server.auth;

import com.jundaodsj.insightops.infrastructure.identity.IdentityRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class LoginRateLimiterTest {

    @Test
    void springUsesTheProductionConstructorWhenTheTestConstructorAlsoExists() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AuthProperties.class);
            context.registerBean(IdentityRepository.class, () -> mock(IdentityRepository.class));
            context.register(LoginRateLimiter.class);
            context.refresh();

            assertThat(context.getBean(LoginRateLimiter.class)).isNotNull();
        }
    }

    @Test
    void locksOnlyTheFailingUsernameAndAddressPair() {
        AuthProperties properties = new AuthProperties();
        properties.setLoginMaxFailures(3);
        Clock clock = Clock.fixed(Instant.parse("2026-08-18T00:00:00Z"), ZoneOffset.UTC);
        LoginRateLimiter limiter = new LoginRateLimiter(properties, clock);

        limiter.failed("owner", "192.0.2.10");
        limiter.failed("owner", "192.0.2.10");
        limiter.failed("owner", "192.0.2.10");

        assertThatThrownBy(() -> limiter.check("OWNER", "192.0.2.10"))
                .isInstanceOf(LoginRateLimiter.LoginRateLimitedException.class);
        assertThatCode(() -> limiter.check("owner", "192.0.2.11")).doesNotThrowAnyException();
        assertThatCode(() -> limiter.check("member", "192.0.2.10")).doesNotThrowAnyException();
    }

    @Test
    void successfulLoginClearsFailures() {
        AuthProperties properties = new AuthProperties();
        properties.setLoginMaxFailures(2);
        LoginRateLimiter limiter = new LoginRateLimiter(properties,
                Clock.fixed(Instant.parse("2026-08-18T00:00:00Z"), ZoneOffset.UTC));
        limiter.failed("owner", "192.0.2.10");
        limiter.succeeded("owner", "192.0.2.10");
        limiter.failed("owner", "192.0.2.10");

        assertThatCode(() -> limiter.check("owner", "192.0.2.10")).doesNotThrowAnyException();
    }
}
