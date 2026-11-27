package com.jundaodsj.insightops.infrastructure.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.jundaodsj.insightops.model.application.AgentPlanningModelGateway;
import com.jundaodsj.insightops.model.application.ModelCallErrorCode;
import com.jundaodsj.insightops.model.application.ModelCallException;
import com.jundaodsj.insightops.model.application.ModelUsage;
import com.openai.client.OpenAIClient;
import com.openai.core.ObjectMappers;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseIncludable;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseUsage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

final class OpenAiResponsesPlanningGateway implements AgentPlanningModelGateway {
    private static final String PROVIDER = "openai";
    private final OpenAIClient client;

    OpenAiResponsesPlanningGateway(OpenAIClient client) {
        this.client = client;
    }

    @Override
    public AgentPlanResponse plan(AgentPlanRequest request) {
        long startedAt = System.nanoTime();
        try {
            var input = inputItems(request);
            var params = ResponseCreateParams.builder().model(request.researchModel().modelId())
                    .instructions(request.systemPrompt()).inputOfResponse(input)
                    .maxOutputTokens(request.maxOutputTokens()).parallelToolCalls(false).store(false)
                    .addInclude(ResponseIncludable.REASONING_ENCRYPTED_CONTENT);
            for (var tool : request.tools()) params.addTool(function(tool));
            var response = client.responses().create(params.build());
            return planResponse(response, input, request.exchanges().size(), startedAt);
        } catch (ModelCallException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw OpenAiModelErrors.classify(PROVIDER, failure);
        }
    }

    private static List<ResponseInputItem> inputItems(AgentPlanRequest request) {
        var input = new ArrayList<ResponseInputItem>();
        var history = (History) request.continuation();
        if (history == null) {
            input.add(ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
                    .role(EasyInputMessage.Role.USER).content(request.userPrompt()).build()));
        } else {
            input.addAll(history.items());
        }
        var exchanges = request.exchanges();
        int firstNewExchange = history == null ? 0 : history.exchangeCount();
        for (var exchange : exchanges.subList(firstNewExchange, exchanges.size())) {
            var call = exchange.toolCall();
            if (history == null) {
                // A caller can supply completed exchanges without a prior Responses turn.
                input.add(ResponseInputItem.ofFunctionCall(ResponseFunctionToolCall.builder()
                        .callId(call.id()).name(call.name()).arguments(call.argumentsJson()).build()));
            }
            input.add(ResponseInputItem.ofFunctionCallOutput(ResponseInputItem.FunctionCallOutput.builder()
                    .callId(call.id()).output(exchange.responseJson()).build()));
        }
        return input;
    }

    private static FunctionTool function(FunctionDefinition definition) {
        try {
            var schema = ObjectMappers.jsonMapper().readValue(definition.inputSchemaJson(), FunctionTool.Parameters.class);
            // Optional registry fields and open argument objects are validated by InsightOps before execution.
            return FunctionTool.builder().name(definition.name()).description(definition.description())
                    .parameters(schema).strict(false).build();
        } catch (JsonProcessingException invalidSchema) {
            throw new IllegalArgumentException("Invalid function schema for " + definition.name(), invalidSchema);
        }
    }

    private static AgentPlanResponse planResponse(Response response, List<ResponseInputItem> input,
            int exchangeCount, long startedAt) {
        if (response.error().isPresent() || response.status().filter(s -> !s.equals(ResponseStatus.COMPLETED)).isPresent()) {
            // An incomplete response can contain a truncated function call. Never pass it to the executor.
            throw new ModelCallException(ModelCallErrorCode.PROVIDER_ERROR, PROVIDER, null);
        }
        var calls = new ArrayList<PlannedToolCall>();
        var text = new StringBuilder();
        for (var item : response.output()) {
            item.functionCall().ifPresent(call -> calls.add(new PlannedToolCall(call.callId(), call.name(), call.arguments())));
            item.message().ifPresent(message -> message.content().forEach(part ->
                    part.outputText().map(ResponseOutputText::text).ifPresent(text::append)));
        }
        if (calls.isEmpty() && text.toString().isBlank()) {
            throw new ModelCallException(ModelCallErrorCode.EMPTY_RESPONSE, PROVIDER, null);
        }
        History next = null;
        if (!calls.isEmpty()) {
            var replay = new ArrayList<>(input);
            for (var item : response.output()) {
                replay.add(ObjectMappers.jsonMapper().convertValue(item, ResponseInputItem.class));
            }
            next = new History(List.copyOf(replay), exchangeCount);
        }
        var model = ObjectMappers.jsonMapper().convertValue(response.model(), String.class);
        var usage = response.usage().map(OpenAiResponsesPlanningGateway::usage).orElseGet(ModelUsage::unknown);
        return new AgentPlanResponse(text.toString(), calls, PROVIDER, model, usage,
                Duration.ofNanos(System.nanoTime() - startedAt), next, response._id().asKnown().orElse(null));
    }

    private static ModelUsage usage(ResponseUsage usage) {
        Long cached = usage._inputTokensDetails().asKnown().map(ResponseUsage.InputTokensDetails::cachedTokens).orElse(null);
        return new ModelUsage(Math.toIntExact(usage.inputTokens()), Math.toIntExact(usage.outputTokens()),
                Math.toIntExact(usage.totalTokens()), cached, null);
    }

    private record History(List<ResponseInputItem> items, int exchangeCount) implements Continuation { }
}
