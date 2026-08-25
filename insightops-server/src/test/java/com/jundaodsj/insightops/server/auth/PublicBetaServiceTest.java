package com.jundaodsj.insightops.server.auth;

import com.jundaodsj.insightops.infrastructure.identity.IdentityRepository;
import com.jundaodsj.insightops.infrastructure.identity.IdentitySecretCipher;
import com.jundaodsj.insightops.infrastructure.identity.PublicBetaRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicBetaServiceTest {
    private final PublicBetaRepository repository = mock(PublicBetaRepository.class);
    private final HumanVerificationService human = mock(HumanVerificationService.class);
    private final IdentityRepository identities = mock(IdentityRepository.class);
    private final IdentitySecretCipher cipher = mock(IdentitySecretCipher.class);
    private final TencentSesProperties tencentSes = new TencentSesProperties();
    private final AuthService auth = mock(AuthService.class);
    private final IdentityProperties identity = new IdentityProperties();
    private final PublicBetaProperties properties = new PublicBetaProperties();
    private final Instant now = Instant.parse("2026-08-26T00:00:00Z");
    private PublicBetaService service;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setOperatorName("Operator");
        properties.setContactEmail("support@example.com");
        properties.getTurnstile().setEnabled(true);
        properties.getTurnstile().setSiteKey("site-key");
        properties.getTurnstile().setSecretKey("secret-key");
        identity.getMail().setEnabled(true);
        tencentSes.setEnabled(true);
        tencentSes.setSecretId("secret-id");
        tencentSes.setSecretKey("secret-key");
        when(human.ready()).thenReturn(true);
        when(repository.control()).thenReturn(new PublicBetaRepository.Control(true, true, null, now));
        when(repository.counts()).thenReturn(new PublicBetaRepository.Counts(3, 2, 5));
        when(auth.encodePassword("StrongPass1")).thenReturn("encoded");
        when(cipher.encrypt(any())).thenReturn("ciphertext");
        when(repository.create(any(), eq(100))).thenAnswer(invocation -> {
            PublicBetaRepository.RegistrationCommand command = invocation.getArgument(0);
            return new PublicBetaRepository.Registration(command.userId(), command.workspaceId(), 6,
                    "PENDING", command.verificationExpiresAt(), null);
        });
        service = new PublicBetaService(repository, properties, human, identity, identities, cipher,
                auth, new PublicBetaMetrics(new SimpleMeterRegistry()), tencentSes, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void reportsCapacityAndServerReadinessWithoutExposingSecrets() {
        PublicBetaService.Status status = service.status();
        assertThat(status.registrationEnabled()).isTrue();
        assertThat(status.activeRegistrations()).isEqualTo(3);
        assertThat(status.occupiedSlots()).isEqualTo(5);
        assertThat(status.turnstileSiteKey()).isEqualTo("site-key");
        assertThat(status.toString()).doesNotContain("secret-key");
    }

    @Test
    void createsPendingPersonalWorkspaceAfterAllConsentsAndHumanVerification() {
        when(human.verify("challenge", "203.0.113.10"))
                .thenReturn(HumanVerificationService.VerificationResult.accepted());
        PublicBetaService.RegistrationResult result = service.register(request(),
                "203.0.113.10", "JUnit");
        assertThat(result.registrationSlot()).isEqualTo(6);
        assertThat(result.verificationExpiresAt()).isEqualTo(now.plusSeconds(86_400));
        verify(repository).create(any(PublicBetaRepository.RegistrationCommand.class), eq(100));
        verify(identities).saveToken(any(), any(), eq("EMAIL_VERIFICATION"), any(), any(), eq(now));
        verify(identities).enqueueMail(any(), eq("beta@example.com"), eq("EMAIL_VERIFICATION"),
                any(), eq("ciphertext"), eq(now));
    }

    @Test
    void failsClosedWhenTurnstileRejectsOrRegistrationSwitchIsOff() {
        when(human.verify("challenge", "203.0.113.10"))
                .thenReturn(HumanVerificationService.VerificationResult.invalid("invalid-input-response"));
        assertThatThrownBy(() -> service.register(request(), "203.0.113.10", "JUnit"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Human verification failed");

        when(repository.control()).thenReturn(new PublicBetaRepository.Control(false, true,
                "Maintenance", now));
        assertThat(service.status().registrationEnabled()).isFalse();
        assertThat(service.status().reason()).isEqualTo("REGISTRATION_SWITCH_OFF");
    }

    @Test
    void springSelectsTheProductionConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(PublicBetaRepository.class, () -> repository);
            context.registerBean(PublicBetaProperties.class, () -> properties);
            context.registerBean(HumanVerificationService.class, () -> human);
            context.registerBean(IdentityProperties.class, () -> identity);
            context.registerBean(IdentityRepository.class, () -> identities);
            context.registerBean(IdentitySecretCipher.class, () -> cipher);
            context.registerBean(AuthService.class, () -> auth);
            context.registerBean(PublicBetaMetrics.class,
                    () -> new PublicBetaMetrics(new SimpleMeterRegistry()));
            context.registerBean(TencentSesProperties.class, () -> tencentSes);
            context.register(PublicBetaService.class);
            context.refresh();
            assertThat(context.getBean(PublicBetaService.class)).isNotNull();
        }
    }
    private static PublicBetaService.RegistrationRequest request() {
        return new PublicBetaService.RegistrationRequest("beta-user", "Beta User", "beta@example.com",
                "StrongPass1", "challenge", true, true, true, true);
    }
}
