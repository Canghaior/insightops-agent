package com.jundaodsj.insightops.server.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TencentSesJavaMailSenderTest {
    private final ObjectMapper json = new ObjectMapper();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = "{\"Response\":{\"MessageId\":\"test-message\",\"RequestId\":\"test-request\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void signsRequestsAndMapsAllThreeTemplatePurposes() throws Exception {
        TencentSesProperties properties = configuredProperties();
        TencentSesJavaMailSender sender = new TencentSesJavaMailSender(properties, json,
                HttpClient.newHttpClient(), Clock.fixed(
                Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC));

        sender.send(message("Verify your InsightOps email", "https://example.test/verify#token=one"));
        sender.send(message("Reset your InsightOps password", "https://example.test/reset#token=two"));
        sender.send(message("You were invited to an InsightOps Workspace",
                "https://example.test/invitations/three"));

        assertThat(bodies).hasSize(3);
        assertPayload(bodies.get(0), 58053, "https://example.test/verify#token=one");
        assertPayload(bodies.get(1), 58054, "https://example.test/reset#token=two");
        assertPayload(bodies.get(2), 58055, "https://example.test/invitations/three");
        assertThat(authorizations).allSatisfy(value -> assertThat(value)
                .startsWith("TC3-HMAC-SHA256 Credential=test-secret-id/2026-08-26/ses/tc3_request")
                .contains("SignedHeaders=content-type;host;x-tc-action", "Signature="));
    }

    @Test
    void failsClosedBeforeNetworkWhenConfigurationIsDisabled() {
        TencentSesProperties properties = configuredProperties();
        properties.setEnabled(false);
        TencentSesJavaMailSender sender = new TencentSesJavaMailSender(properties, json,
                HttpClient.newHttpClient(), Clock.systemUTC());

        assertThatThrownBy(() -> sender.send(message("Verify your InsightOps email",
                "https://example.test/verify#token=one")))
                .isInstanceOf(MailSendException.class)
                .hasMessageContaining("not fully configured");
        assertThat(bodies).isEmpty();
    }

    private TencentSesProperties configuredProperties() {
        TencentSesProperties value = new TencentSesProperties();
        value.setEnabled(true);
        value.setSecretId("test-secret-id");
        value.setSecretKey("test-secret-key");
        value.setRegion("ap-guangzhou");
        value.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
        value.setFromAddress("no-reply@mail.canghaior.com");
        value.setFromName("InsightOps Agent");
        value.setEmailVerificationTemplateId(58053);
        value.setPasswordResetTemplateId(58054);
        value.setWorkspaceInvitationTemplateId(58055);
        return value;
    }

    private static SimpleMailMessage message(String subject, String link) {
        SimpleMailMessage value = new SimpleMailMessage();
        value.setTo("recipient@example.com");
        value.setSubject(subject);
        value.setText("Open this one-time link:\n\n" + link + "\n\nIt expires soon.");
        return value;
    }

    private void assertPayload(String body, long templateId, String link) throws Exception {
        JsonNode payload = json.readTree(body);
        assertThat(payload.path("FromEmailAddress").asText())
                .isEqualTo("InsightOps Agent <no-reply@mail.canghaior.com>");
        assertThat(payload.path("Destination").get(0).asText()).isEqualTo("recipient@example.com");
        assertThat(payload.path("Template").path("TemplateID").asLong()).isEqualTo(templateId);
        JsonNode templateData = json.readTree(payload.path("Template").path("TemplateData").asText());
        assertThat(templateData.path("link").asText()).isEqualTo(link);
    }
}
