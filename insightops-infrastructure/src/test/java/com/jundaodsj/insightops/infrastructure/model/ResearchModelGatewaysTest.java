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
    private final LinkedBlockingQueue<ResponseFixture> responseFixtures = new LinkedBlockingQueue<>();
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
    void stop() {
        gateways.close();
        http.stop(0);
    }

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
                assertThat(request.path()).isEqualTo("/openai/v1/responses");
                assertThat(body.path("max_output_tokens").asInt()).isEqualTo(64);
                assertThat(body.path("store").booleanValue()).isFalse();
                assertThat(body.path("parallel_tool_calls").booleanValue()).isFalse();
                assertThat(body.path("tools").get(0).path("type").asText()).isEqualTo("function");
                assertThat(body.path("tools").get(0).path("strict").asBoolean(true)).isFalse();
                assertThat(body.path("tools").get(0).path("parameters").path("type").asText()).isEqualTo("object");
                assertThat(body.path("instructions").asText()).isEqualTo("system");
                assertThat(body.path("input").get(2).path("output").asText()).isEqualTo("{\"found\":true}");
                assertThat(body.has("thinking") || body.has("max_tokens") || body.has("temperature")).isFalse();
            } else {
                assertThat(request.path()).isEqualTo("/deepseek/chat/completions");
                assertThat(body.path("thinking").path("type").asText()).isEqualTo(model.thinking() ? "enabled" : "disabled");
                assertThat(body.path("max_tokens").asInt()).isEqualTo(64);
                var messages = body.path("messages");
                assertThat(messages.get(messages.size() - 1).path("role").asText())
                        .isEqualTo(model.thinking() ? "user" : "tool");
                assertThat(messages.toString()).contains("found");
            }
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
        var request = requests.remove();
        assertThat(request.path()).isEqualTo("/openai/v1/chat/completions");
        assertThat(request.body().path("model").asText()).isEqualTo("gpt-6.2-sol");
        assertThat(request.body().has("tools")).isFalse();
    }

    @Test
    void replaysEncryptedReasoningAndNativeToolResultsInOrderOnlyWithinThatRun() {
        var functions = List.of(new FunctionDefinition("lookup", "Lookup", "{\"type\":\"object\"}"));
        String turn = """
                [{"type":"reasoning","id":"rs_test","summary":[],"encrypted_content":"opaque-test"},
                 {"type":"message","id":"msg_work","role":"assistant","status":"completed","phase":"commentary",
                  "content":[{"type":"output_text","text":"Checking.","annotations":[]}]},
                 {"type":"function_call","id":"fc_test","call_id":"call_test","name":"lookup","arguments":"{}"}]
                """;
        responseFixtures.add(new ResponseFixture(200, response("gpt-6.2-sol", "completed", turn)));
        var first = gateways.plan(new AgentPlanRequest("system", "run one", List.of(), functions,
                0, 128, null, ResearchModel.GPT_62_SOL));
        assertThat(requests.remove().body().path("input")).hasSize(1);
        assertThat(first.toolCalls()).containsExactly(new PlannedToolCall("call_test", "lookup", "{}"));
        assertThat(first.content()).isEqualTo("Checking.");
        assertThat(first.continuation()).isNotNull();
        assertThat(first.usage()).isEqualTo(new ModelUsage(27, 5, 32, 4L, null));

        var other = gateways.plan(new AgentPlanRequest("system", "independent", List.of(), functions,
                0, 128, null, ResearchModel.GPT_62_SOL));
        var otherRequest = requests.remove().body();
        assertThat(otherRequest.path("input")).hasSize(1);
        assertThat(otherRequest.toString()).doesNotContain("opaque-test", "run one");
        assertThat(other.continuation()).isNull();

        var result = gateways.plan(new AgentPlanRequest("system", "run one",
                List.of(new ToolExchange(first.toolCalls().getFirst(), "{\"hits\":1}")), functions,
                0, 128, null, ResearchModel.GPT_62_SOL, first.continuation()));
        var body = requests.remove().body();
        var input = body.path("input");
        assertThat(input).hasSize(5);
        assertThat(input.get(1).path("encrypted_content").asText()).isEqualTo("opaque-test");
        assertThat(input.get(2).path("phase").asText()).isEqualTo("commentary");
        assertThat(input.get(3).path("id").asText()).isEqualTo("fc_test");
        assertThat(input.get(4).path("type").asText()).isEqualTo("function_call_output");
        assertThat(input.get(4).path("call_id").asText()).isEqualTo("call_test");
        assertThat(input.get(4).path("output").asText()).isEqualTo("{\"hits\":1}");
        assertThat(body.has("previous_response_id")).isFalse();
        assertThat(result.content()).isEqualTo("FINISH");
        assertThat(result.continuation()).isNull();
    }

    @Test
    void refusesToExecuteIncompleteToolCallsAndNeverFallsBackOnRejection() {
        var req = new AgentPlanRequest("system", "synthetic", List.of(),
                List.of(new FunctionDefinition("lookup", "Lookup", "{\"type\":\"object\"}")),
                0, 128, null, ResearchModel.GPT_62_SOL);
        responseFixtures.add(new ResponseFixture(200, response("gpt-6.2-sol", "incomplete",
                "[{\"type\":\"function_call\",\"id\":\"fc_cut\",\"call_id\":\"cut\",\"name\":\"lookup\",\"arguments\":\"{\"}]")));
        assertThatThrownBy(() -> gateways.plan(req)).isInstanceOfSatisfying(ModelCallException.class,
                error -> assertThat(error.code()).isEqualTo(ModelCallErrorCode.PROVIDER_ERROR));
        responseFixtures.add(new ResponseFixture(400, "{\"error\":{\"message\":\"Rejected\",\"type\":\"invalid_request_error\",\"param\":\"tools\"}}"));
        assertThatThrownBy(() -> gateways.plan(req)).isInstanceOfSatisfying(ModelCallException.class,
                error -> assertThat(error.code()).isEqualTo(ModelCallErrorCode.REQUEST_REJECTED));
        assertThat(requests).extracting(Request::path).containsExactly("/openai/v1/responses", "/openai/v1/responses");
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
            if (exchange.getRequestURI().getPath().equals("/openai/v1/responses")) {
                var fixture = responseFixtures.poll();
                String finish = """
                        [{"type":"message","id":"msg_final","role":"assistant","status":"completed",
                          "content":[{"type":"output_text","text":"FINISH","annotations":[]}]}]
                        """;
                if (fixture == null) fixture = new ResponseFixture(200, response(body.path("model").asText(), "completed", finish));
                byte[] data = fixture.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(fixture.status(), data.length);
                exchange.getResponseBody().write(data);
                return;
            }
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

    private static String response(String model, String status, String output) {
        return """
                {"id":"resp_test","object":"response","model":"%s","status":"%s","output":%s,
                 "usage":{"input_tokens":27,"input_tokens_details":{"cached_tokens":4},"output_tokens":5,
                          "output_tokens_details":{"reasoning_tokens":2},"total_tokens":32}}
                """.formatted(model, status, output);
    }

    private record ResponseFixture(int status, String body) { }
}
