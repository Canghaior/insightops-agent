package com.jundaodsj.insightops.infrastructure.delivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.report.application.ReportDeliveryGateway;
import com.jundaodsj.insightops.report.application.ReportDeliveryStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class WebhookReportDeliveryGateway implements ReportDeliveryGateway {
    private static final String USER_AGENT = "InsightOpsAgent/0.1 (+report-delivery)";
    private final HttpClient http;
    private final ObjectMapper json;
    private final Duration requestTimeout;
    private final String publicBaseUrl;

    @Autowired
    public WebhookReportDeliveryGateway(ObjectMapper json,
            @Value("${insightops.delivery.request-timeout-seconds:20}") int requestTimeoutSeconds,
            @Value("${insightops.delivery.public-base-url:https://insightops.canghaior.com}") String publicBaseUrl) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build(), json,
                Duration.ofSeconds(Math.max(3, requestTimeoutSeconds)), publicBaseUrl);
    }

    WebhookReportDeliveryGateway(HttpClient http, ObjectMapper json,
                                 Duration requestTimeout, String publicBaseUrl) {
        this.http = http;
        this.json = json;
        this.requestTimeout = requestTimeout;
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.replaceAll("/+$", "");
    }

    @Override
    public DeliveryResult deliver(ReportDeliveryStore.DeliveryTask task) {
        long started = System.nanoTime();
        URI endpoint;
        try {
            endpoint = WebhookUrlPolicy.resolvedPublic(task.endpointUrl());
        } catch (IllegalArgumentException exception) {
            throw failure("INVALID_ENDPOINT", exception.getMessage(), null, started, false, exception);
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Idempotency-Key", task.deliveryId().toString())
                .POST(HttpRequest.BodyPublishers.ofString(payload(task), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            long durationMs = elapsed(started);
            int status = response.statusCode();
            if (status >= 200 && status < 300) return new DeliveryResult(status, durationMs);
            boolean retryable = status == 408 || status == 425 || status == 429 || status >= 500;
            throw new ReportDeliveryGateway.DeliveryException("HTTP_" + status,
                    "Webhook returned HTTP " + status, status, durationMs, retryable, null);
        } catch (java.net.http.HttpTimeoutException exception) {
            throw failure("TIMEOUT", "Webhook request timed out", null, started, true, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure("INTERRUPTED", "Webhook request was interrupted", null, started, true, exception);
        } catch (IOException exception) {
            throw failure("NETWORK_ERROR", "Webhook request failed", null, started, true, exception);
        }
    }

    private String payload(ReportDeliveryStore.DeliveryTask task) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("id", task.reportId());
        report.put("title", task.reportTitle());
        report.put("periodStart", task.periodStart());
        report.put("periodEnd", task.periodEnd());
        report.put("itemCount", task.itemCount());
        report.put("highRiskCount", task.highRiskCount());
        report.put("markdown", task.markdownExcerpt());
        report.put("url", publicBaseUrl + "/reports?report=" + task.reportId());
        try {
            return json.writeValueAsString(Map.of("event", "insightops.report.ready", "report", report));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize webhook payload", exception);
        }
    }

    private static ReportDeliveryGateway.DeliveryException failure(
            String code, String message, Integer responseCode, long started,
            boolean retryable, Throwable cause) {
        return new ReportDeliveryGateway.DeliveryException(
                code, message, responseCode, elapsed(started), retryable, cause);
    }

    private static long elapsed(long started) {
        return Math.max(0, Duration.ofNanos(System.nanoTime() - started).toMillis());
    }
}
