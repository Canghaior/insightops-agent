package com.jundaodsj.insightops.infrastructure.model;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import com.jundaodsj.insightops.infrastructure.config.DeepSeekModelProperties;
import com.jundaodsj.insightops.model.application.AgentPlanningModelGateway;
import com.jundaodsj.insightops.model.application.ModelCallErrorCode;
import com.jundaodsj.insightops.model.application.ModelCallException;
import com.jundaodsj.insightops.model.application.ModelUsage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "insightops.model.deepseek", name = "enabled", havingValue = "true")
public class SpringAiDeepSeekPlanningModelGateway implements AgentPlanningModelGateway {

    private static final String PROVIDER = "deepseek";

    private final ChatModel chatModel;
    private final DeepSeekModelProperties properties;

    public SpringAiDeepSeekPlanningModelGateway(
            ChatModel chatModel, DeepSeekModelProperties properties) {
        this.chatModel = chatModel;
        this.properties = properties;
    }

    @Override
    public AgentPlanResponse plan(AgentPlanRequest request) {
        long startedAt = System.nanoTime();
        try {
            ChatResponse response = chatModel.call(prompt(request));
            AssistantMessage output = response == null || response.getResult() == null
                    ? null : response.getResult().getOutput();
            if (output == null) {
                throw new ModelCallException(ModelCallErrorCode.EMPTY_RESPONSE, PROVIDER, null);
            }
            List<PlannedToolCall> toolCalls = output.getToolCalls().stream()
                    .map(call -> new PlannedToolCall(call.id(), call.name(), call.arguments()))
                    .toList();
            String content = output.getText() == null ? "" : output.getText();
            if (content.isBlank() && toolCalls.isEmpty()) {
                throw new ModelCallException(ModelCallErrorCode.EMPTY_RESPONSE, PROVIDER, null);
            }
            String responseModel = response.getMetadata() == null
                    || response.getMetadata().getModel() == null
                    || response.getMetadata().getModel().isBlank()
                    ? properties.model() : response.getMetadata().getModel();
            Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
            return new AgentPlanResponse(
                    content, toolCalls, PROVIDER, responseModel, toModelUsage(usage),
                    Duration.ofNanos(System.nanoTime() - startedAt));
        }
        catch (ModelCallException exception) {
            throw exception;
        }
        catch (RuntimeException exception) {
            throw classify(exception);
        }
    }

    private Prompt prompt(AgentPlanRequest request) {
        List<ToolCallback> callbacks = request.tools().stream()
                .map(SpringAiDeepSeekPlanningModelGateway::definitionOnlyCallback)
                .toList();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(properties.model())
                .temperature(request.temperature())
                .maxTokens(request.maxOutputTokens())
                .timeout(Duration.ofSeconds(properties.requestTimeoutSeconds()))
                .maxRetries(properties.maxRetries())
                .toolCallbacks(callbacks)
                .toolChoice("auto")
                .parallelToolCalls(false)
                .extraBody(Map.of(
                        "thinking",
                        Map.of("type", properties.thinkingEnabled() ? "enabled" : "disabled")))
                .build();
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(request.systemPrompt()));
        messages.add(new UserMessage(request.userPrompt()));
        for (ToolExchange exchange : request.exchanges()) {
            PlannedToolCall call = exchange.toolCall();
            messages.add(AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            call.id(), "function", call.name(), call.argumentsJson())))
                    .build());
            messages.add(ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            call.id(), call.name(), exchange.responseJson())))
                    .build());
        }
        return new Prompt(List.copyOf(messages), options);
    }

    private static ToolCallback definitionOnlyCallback(FunctionDefinition definition) {
        return new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return new DefaultToolDefinition(
                        definition.name(), definition.description(), definition.inputSchemaJson());
            }

            @Override
            public String call(String toolInput) {
                throw new IllegalStateException(
                        "InsightOps Executor, not Spring AI, owns registered tool execution");
            }
        };
    }

    private static ModelCallException classify(Throwable exception) {
        Throwable candidate = exception;
        while (candidate.getCause() != null && candidate != candidate.getCause()) {
            if (candidate instanceof OpenAIRetryableException
                    || candidate instanceof OpenAIIoException
                    || candidate instanceof OpenAIServiceException) {
                break;
            }
            candidate = candidate.getCause();
        }
        if (candidate instanceof OpenAIRetryableException || candidate instanceof OpenAIIoException) {
            return new ModelCallException(
                    ModelCallErrorCode.TEMPORARILY_UNAVAILABLE, PROVIDER, exception);
        }
        if (candidate instanceof OpenAIServiceException serviceException) {
            ModelCallErrorCode code = serviceException.statusCode() == 408
                    || serviceException.statusCode() == 429
                    || serviceException.statusCode() >= 500
                    ? ModelCallErrorCode.TEMPORARILY_UNAVAILABLE
                    : ModelCallErrorCode.REQUEST_REJECTED;
            return new ModelCallException(code, PROVIDER, exception);
        }
        return new ModelCallException(ModelCallErrorCode.PROVIDER_ERROR, PROVIDER, exception);
    }

    private static ModelUsage toModelUsage(Usage usage) {
        if (usage == null) return ModelUsage.unknown();
        return new ModelUsage(
                usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens(),
                usage.getCacheReadInputTokens(), usage.getCacheWriteInputTokens());
    }
}
