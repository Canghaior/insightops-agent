package com.jundaodsj.insightops.infrastructure.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.infrastructure.config.DeepSeekModelProperties;
import com.jundaodsj.insightops.infrastructure.config.ResearchModelCatalog;
import com.jundaodsj.insightops.model.application.AgentPlanningModelGateway.*;
import com.jundaodsj.insightops.model.application.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class ResearchModelGatewaysTest {
    private final ObjectMapper json = new ObjectMapper();
    private final LinkedBlockingQueue<Request> requests = new LinkedBlockingQueue<>();
    private HttpServer http;
    private ResearchModelGateways gateways;
    private ResearchModelCatalog catalog;

    @BeforeEach
    void start() throws IOException {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", this::respond);
        http.start();
        var url = "http://127.0.0.1:" + http.getAddress().getPort();
        var settings = new DeepSeekModelProperties(true, url + "/deepseek", "deepseek-v4-flash",
                false, .2, 128, 2, 10, 0, false);
        catalog = new ResearchModelCatalog(settings, "mock-openai", "mock-deepseek", "");
        gateways = new ResearchModelGateways(settings, catalog, null, null, "mock-openai", url + "/openai/v1", "mock-deepseek");
    }

    @AfterEach
    void stop() { http.stop(0); }

    @Test
    void selectedModelControlsProviderCredentialsPlanningAndThinkingMode() {
        assertThat(catalog.available()).containsExactly(ResearchModel.values());
        assertThatThrownBy(() -> catalog.resolve("arbitrary-model")).isInstanceOf(IllegalArgumentException.class);
        for (var model : ResearchModel.values()) {
            var exchange = new ToolExchange(new PlannedToolCall("one", "lookup", "{}"), "{\"found\":true}");
            var result = gateways.plan(new AgentPlanRequest("system", "synthetic", List.of(exchange),
                    List.of(new FunctionDefinition("lookup", "lookup", "{\"type\":\"object\"}")), .2, 64,
                    "ignored-legacy-planner-override", model));
            var request = requests.remove();
            var body = request.body();
            assertThat(body.path("model").asText()).isEqualTo(model.modelId());
            assertThat(result.model()).isEqualTo(model.modelId());
            assertThat(result.provider()).isEqualTo(model.provider());
            assertThat(request.authorization()).isEqualTo("Bearer mock-" + model.provider());
            if ("openai".equals(model.provider())) {
                assertThat(request.path()).isEqualTo("/openai/v1/chat/completions");
                assertThat(body.path("max_completion_tokens").asInt()).isEqualTo(64);
                assertThat(body.has("thinking") || body.has("max_tokens") || body.has("temperature")).isFalse();
            } else {
                assertThat(request.path()).isEqualTo("/deepseek/chat/completions");
                assertThat(body.path("thinking").path("type").asText()).isEqualTo(model.thinking() ? "enabled" : "disabled");
                assertThat(body.path("max_tokens").asInt()).isEqualTo(64);
            }
            var messages = body.path("messages");
            assertThat(messages.get(messages.size() - 1).path("role").asText()).isEqualTo(model.thinking() ? "user" : "tool");
            assertThat(messages.toString()).contains("found");
        }
    }

    @Test
    void selectedModelStreamsAnAnswerFromItsProvider() throws Exception {
        var terminal = new CompletableFuture<ChatStreamEvent>();
        var text = new StringBuilder();
        gateways.stream(new ChatModelRequest("system", "synthetic", .2, 64, ResearchModel.GPT_62_SOL), new ChatStreamListener() {
            public void onEvent(ChatStreamEvent event) {
                if (event.type() == ChatStreamEventType.COMPLETED) terminal.complete(event);
                else text.append(event.content());
            }
            public void onError(ModelCallException error) { terminal.completeExceptionally(error); }
        });
        assertThat(terminal.get(8, TimeUnit.SECONDS).provider()).isEqualTo("openai");
        assertThat(text.toString()).isEqualTo("OK");
        assertThat(requests.remove().body().path("model").asText()).isEqualTo("gpt-6.2-sol");
    }

    @Test
    void allowlistAndCredentialsControlAvailableModelsAndDefault() {
        var settings = new DeepSeekModelProperties(false, "", "", false, .2, 128, 2, 10, 0, false);
        var restricted = new ResearchModelCatalog(settings, "mock-openai", "", "gpt-6.1-terra");
        assertThat(restricted.available()).containsExactly(ResearchModel.GPT_61_TERRA);
        assertThat(restricted.resolve(null)).isEqualTo(ResearchModel.GPT_61_TERRA);
        assertThatThrownBy(() -> restricted.resolve("gpt-6.2-astra")).isInstanceOf(IllegalArgumentException.class);
    }

    private void respond(HttpExchange exchange) throws IOException {
        try (exchange) {
            var body = json.readTree(exchange.getRequestBody());
            requests.add(new Request(exchange.getRequestURI().getPath(), exchange.getRequestHeaders().getFirst("Authorization"), body));
            boolean stream = body.path("stream").asBoolean(false);
            String message = stream
                    ? "data: {\"id\":\"test\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"%s\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                    : "{\"id\":\"test\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"%s\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"FINISH\"},\"finish_reason\":\"stop\"}]}";
            byte[] data = message.formatted(body.path("model").asText()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
        }
    }

    private record Request(String path, String authorization, JsonNode body) { }
}
