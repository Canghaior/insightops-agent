package com.jundaodsj.insightops.server.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

@Component
public class CloudflareTurnstileVerifier implements HumanVerificationService {
    private static final Logger log = LoggerFactory.getLogger(CloudflareTurnstileVerifier.class);
    private final PublicBetaProperties properties;
    private final RestClient client;

    public CloudflareTurnstileVerifier(PublicBetaProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(Math.max(2, properties.getTurnstile().getTimeoutSeconds()));
        requestFactory.setReadTimeout(timeout);
        this.client = builder.requestFactory(requestFactory).build();
    }

    @Override
    public boolean ready() {
        PublicBetaProperties.Turnstile value = properties.getTurnstile();
        return value.isEnabled() && present(value.getSiteKey()) && present(value.getSecretKey())
                && present(value.getExpectedHostname()) && present(value.getExpectedAction());
    }

    @Override
    public VerificationResult verify(String token, String remoteAddress) {
        if (!ready() || token == null || token.isBlank() || token.length() > 4096) {
            return VerificationResult.invalid("TURNSTILE_NOT_READY_OR_TOKEN_MISSING");
        }
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("secret", properties.getTurnstile().getSecretKey());
            form.add("response", token);
            if (present(remoteAddress)) form.add("remoteip", remoteAddress.strip());
            SiteVerifyResponse response = client.post()
                    .uri(properties.getTurnstile().getVerifyUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(SiteVerifyResponse.class);
            if (response == null || !response.success()) {
                return VerificationResult.invalid(firstError(response));
            }
            if (!properties.getTurnstile().getExpectedHostname().equalsIgnoreCase(response.hostname())) {
                return VerificationResult.invalid("HOSTNAME_MISMATCH");
            }
            if (!properties.getTurnstile().getExpectedAction().equals(response.action())) {
                return VerificationResult.invalid("ACTION_MISMATCH");
            }
            return VerificationResult.accepted();
        } catch (RuntimeException exception) {
            log.warn("Turnstile verification failed errorType={}", exception.getClass().getSimpleName());
            return VerificationResult.invalid("TURNSTILE_UNAVAILABLE");
        }
    }

    private static String firstError(SiteVerifyResponse response) {
        if (response == null || response.errorCodes() == null || response.errorCodes().isEmpty()) {
            return "TURNSTILE_REJECTED";
        }
        String value = response.errorCodes().getFirst();
        return value == null || value.isBlank() ? "TURNSTILE_REJECTED" : value;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    record SiteVerifyResponse(boolean success, String hostname, String action,
                              @JsonProperty("error-codes") List<String> errorCodes) { }
}
