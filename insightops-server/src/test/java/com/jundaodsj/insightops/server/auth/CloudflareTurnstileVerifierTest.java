package com.jundaodsj.insightops.server.auth;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CloudflareTurnstileVerifierTest {
    private final AtomicReference<String> response = new AtomicReference<>();
    private final AtomicReference<String> form = new AtomicReference<>();
    private HttpServer server;
    private CloudflareTurnstileVerifier verifier;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/turnstile/v0/siteverify", exchange -> {
            form.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        PublicBetaProperties properties = new PublicBetaProperties();
        PublicBetaProperties.Turnstile turnstile = properties.getTurnstile();
        turnstile.setEnabled(true);
        turnstile.setSiteKey("test-site-key");
        turnstile.setSecretKey("test-secret-key");
        turnstile.setExpectedHostname("insightops.canghaior.com");
        turnstile.setExpectedAction("register");
        turnstile.setVerifyUrl("http://127.0.0.1:" + server.getAddress().getPort()
                + "/turnstile/v0/siteverify");
        verifier = new CloudflareTurnstileVerifier(properties, RestClient.builder());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void acceptsOnlyTheConfiguredHostnameAndAction() {
        response.set("{\"success\":true,\"hostname\":\"insightops.canghaior.com\","
                + "\"action\":\"register\",\"error-codes\":[]}");

        HumanVerificationService.VerificationResult result =
                verifier.verify("test-token", "203.0.113.8");

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCode()).isNull();
        assertThat(form.get()).contains("secret=test-secret-key", "response=test-token",
                "remoteip=203.0.113.8");
    }

    @Test
    void rejectsAValidTokenIssuedForAnotherHostname() {
        response.set("{\"success\":true,\"hostname\":\"other.example.com\","
                + "\"action\":\"register\",\"error-codes\":[]}");

        HumanVerificationService.VerificationResult result =
                verifier.verify("test-token", "203.0.113.8");

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCode()).isEqualTo("HOSTNAME_MISMATCH");
    }

    @Test
    void preservesCloudflareFailureCodesAndFailsClosed() {
        response.set("{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}");

        HumanVerificationService.VerificationResult result =
                verifier.verify("bad-token", "203.0.113.8");

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCode()).isEqualTo("invalid-input-response");
    }
}
