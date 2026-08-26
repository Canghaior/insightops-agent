package com.jundaodsj.insightops.server.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.InputStreamSource;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessagePreparator;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Primary
@Component
@ConditionalOnProperty(prefix = "insightops.tencent-ses", name = "enabled", havingValue = "true")
public class TencentSesJavaMailSender implements JavaMailSender {
    private static final String SERVICE = "ses";
    private static final String ACTION = "SendEmail";
    private static final String VERSION = "2020-10-02";
    private static final String CONTENT_TYPE = "application/json; charset=utf-8";
    private static final Pattern LINK = Pattern.compile("https?://\\S+");
    private final TencentSesProperties properties;
    private final ObjectMapper json;
    private final HttpClient http;
    private final Clock clock;

    @Autowired
    public TencentSesJavaMailSender(TencentSesProperties properties, ObjectMapper json) {
        this(properties, json, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(2, properties.getTimeoutSeconds())))
                .build(), Clock.systemUTC());
    }

    TencentSesJavaMailSender(TencentSesProperties properties, ObjectMapper json,
                             HttpClient http, Clock clock) {
        this.properties = properties;
        this.json = json;
        this.http = http;
        this.clock = clock;
    }

    @Override
    public void send(SimpleMailMessage simpleMessage) throws MailException {
        if (simpleMessage == null) throw new MailSendException("Mail message is required");
        send(new SimpleMailMessage[]{simpleMessage});
    }

    @Override
    public void send(SimpleMailMessage... messages) throws MailException {
        if (!properties.isReady()) throw new MailSendException("Tencent SES is not fully configured");
        if (messages == null) return;
        for (SimpleMailMessage message : messages) sendOne(message);
    }

    private void sendOne(SimpleMailMessage message) {
        try {
            String recipient = singleRecipient(message);
            String subject = message.getSubject() == null ? "InsightOps notification" : message.getSubject();
            long templateId = templateId(subject);
            String templateData = json.writeValueAsString(Map.of("link", templateLink(templateId, message.getText())));
            Map<String, Object> template = new LinkedHashMap<>();
            template.put("TemplateID", templateId);
            template.put("TemplateData", templateData);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("FromEmailAddress", fromAddress());
            payload.put("Destination", new String[]{recipient});
            payload.put("Subject", subject);
            payload.put("Template", template);
            if (present(properties.getReplyTo())) payload.put("ReplyToAddresses", properties.getReplyTo().trim());
            byte[] body = json.writeValueAsBytes(payload);
            Instant now = clock.instant();
            long timestamp = now.getEpochSecond();
            String host = URI.create(properties.getEndpoint()).getHost();
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getEndpoint()))
                    .timeout(Duration.ofSeconds(Math.max(2, properties.getTimeoutSeconds())))
                    .header("Content-Type", CONTENT_TYPE)
                    .header("X-TC-Action", ACTION)
                    .header("X-TC-Version", VERSION)
                    .header("X-TC-Region", properties.getRegion())
                    .header("X-TC-Timestamp", String.valueOf(timestamp))
                    .header("Authorization", authorization(host, body, now, timestamp))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode envelope = json.readTree(response.body()).path("Response");
            if (response.statusCode() / 100 != 2 || envelope.has("Error")) {
                String code = envelope.path("Error").path("Code").asText("SES_REQUEST_FAILED");
                throw new MailSendException("Tencent SES rejected the request: " + code);
            }
        } catch (MailException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MailSendException("Tencent SES request was interrupted", exception);
        } catch (Exception exception) {
            throw new MailSendException("Tencent SES request failed", exception);
        }
    }

    private String authorization(String host, byte[] payload, Instant now, long timestamp) throws Exception {
        String date = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC).format(now);
        String canonicalHeaders = "content-type:" + CONTENT_TYPE + "\nhost:" + host
                + "\nx-tc-action:" + ACTION.toLowerCase() + "\n";
        String signedHeaders = "content-type;host;x-tc-action";
        String canonicalRequest = "POST\n/\n\n" + canonicalHeaders + "\n" + signedHeaders
                + "\n" + sha256(payload);
        String scope = date + "/" + SERVICE + "/tc3_request";
        String stringToSign = "TC3-HMAC-SHA256\n" + timestamp + "\n" + scope + "\n"
                + sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        byte[] secretDate = hmac(("TC3" + properties.getSecretKey()).getBytes(StandardCharsets.UTF_8), date);
        byte[] secretService = hmac(secretDate, SERVICE);
        byte[] secretSigning = hmac(secretService, "tc3_request");
        String signature = HexFormat.of().formatHex(hmac(secretSigning, stringToSign));
        return "TC3-HMAC-SHA256 Credential=" + properties.getSecretId() + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
    }

    private static byte[] hmac(byte[] key, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private long templateId(String subject) {
        String value = subject.toLowerCase();
        if (value.contains("reset")) return properties.getPasswordResetTemplateId();
        if (value.contains("invited") || value.contains("invitation")) {
            return properties.getWorkspaceInvitationTemplateId();
        }
        return properties.getEmailVerificationTemplateId();
    }

    private String fromAddress() {
        return present(properties.getFromName())
                ? properties.getFromName().trim() + " <" + properties.getFromAddress().trim() + ">"
                : properties.getFromAddress().trim();
    }

    private static String singleRecipient(SimpleMailMessage message) {
        String[] recipients = message.getTo();
        if (recipients == null || recipients.length != 1 || !present(recipients[0])) {
            throw new MailSendException("Exactly one mail recipient is required");
        }
        return recipients[0].trim();
    }

    private static String extractLink(String body) {
        Matcher matcher = LINK.matcher(body == null ? "" : body);
        if (!matcher.find()) throw new MailSendException("Mail template link is missing");
        return matcher.group();
    }

    private String templateLink(long templateId, String body) {
        URI value;
        try {
            value = URI.create(extractLink(body));
        } catch (IllegalArgumentException exception) {
            throw new MailSendException("Mail template link is invalid", exception);
        }
        String expectedPath;
        if (templateId == properties.getPasswordResetTemplateId()) {
            expectedPath = "/reset-password";
        } else if (templateId == properties.getWorkspaceInvitationTemplateId()) {
            expectedPath = "/invitation";
        } else {
            expectedPath = "/verify-email";
        }
        if (!expectedPath.equals(value.getRawPath()) || present(value.getRawQuery())
                || !present(value.getRawFragment()) || !value.getRawFragment().startsWith("token=")) {
            throw new MailSendException("Mail template link does not match its fixed InsightOps route");
        }
        return expectedPath.substring(1) + "#" + value.getRawFragment();
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }

    @Override public MimeMessage createMimeMessage() { return new MimeMessage(Session.getInstance(new Properties())); }
    @Override public MimeMessage createMimeMessage(InputStream contentStream) throws MailException {
        try { return new MimeMessage(Session.getInstance(new Properties()), contentStream); }
        catch (Exception exception) { throw new MailSendException("Unable to parse MIME message", exception); }
    }
    @Override public void send(MimeMessage mimeMessage) { unsupported(); }
    @Override public void send(MimeMessage... mimeMessages) { unsupported(); }
    @Override public void send(MimeMessagePreparator mimeMessagePreparator) { unsupported(); }
    @Override public void send(MimeMessagePreparator... mimeMessagePreparators) { unsupported(); }
    private static void unsupported() { throw new MailSendException("Tencent SES adapter supports template messages only"); }
}
