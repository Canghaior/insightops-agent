package com.jundaodsj.insightops.infrastructure.model;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import com.jundaodsj.insightops.infrastructure.config.DeepSeekModelProperties;
import com.jundaodsj.insightops.model.application.ChatModelGateway;
import com.jundaodsj.insightops.model.application.ChatModelRequest;
import com.jundaodsj.insightops.model.application.ChatModelResponse;
import com.jundaodsj.insightops.model.application.ChatStreamEvent;
import com.jundaodsj.insightops.model.application.ChatStreamListener;
import com.jundaodsj.insightops.model.application.ChatStreamSession;
import com.jundaodsj.insightops.model.application.ModelCallErrorCode;
import com.jundaodsj.insightops.model.application.ModelCallException;
import com.jundaodsj.insightops.model.application.ModelUsage;
import com.jundaodsj.insightops.model.application.StreamingChatModelGateway;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Component
@ConditionalOnProperty(prefix = "insightops.model.deepseek", name = "enabled", havingValue = "true")
public class SpringAiDeepSeekChatModelGateway implements ChatModelGateway, StreamingChatModelGateway {

    private static final String PROVIDER = "deepseek";

    private final ChatModel chatModel;
    private final DeepSeekModelProperties properties;

    public SpringAiDeepSeekChatModelGateway(ChatModel chatModel, DeepSeekModelProperties properties) {
        this.chatModel = chatModel;
        this.properties = properties;
    }

    @Override
    public ChatModelResponse generate(ChatModelRequest request) {
        long startedAt = System.nanoTime();
        try {
            ChatResponse response = chatModel.call(prompt(request));
            String content = response == null || response.getResult() == null
                    ? null
                    : response.getResult().getOutput().getText();
            if (content == null || content.isBlank()) {
                throw new ModelCallException(ModelCallErrorCode.EMPTY_RESPONSE, PROVIDER, null);
            }

            String responseModel = response.getMetadata() == null
                    || response.getMetadata().getModel() == null
                    || response.getMetadata().getModel().isBlank()
                    ? properties.model()
                    : response.getMetadata().getModel();
            Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
            return new ChatModelResponse(
                    content,
                    PROVIDER,
                    responseModel,
                    toModelUsage(usage),
                    Duration.ofNanos(System.nanoTime() - startedAt));
        }
        catch (ModelCallException exception) {
            throw exception;
        }
        catch (OpenAIRetryableException | OpenAIIoException exception) {
            throw new ModelCallException(ModelCallErrorCode.TEMPORARILY_UNAVAILABLE, PROVIDER, exception);
        }
        catch (OpenAIServiceException exception) {
            ModelCallErrorCode code = exception.statusCode() == 408
                    || exception.statusCode() == 429
                    || exception.statusCode() >= 500
                    ? ModelCallErrorCode.TEMPORARILY_UNAVAILABLE
                    : ModelCallErrorCode.REQUEST_REJECTED;
            throw new ModelCallException(code, PROVIDER, exception);
        }
        catch (RuntimeException exception) {
            throw classify(exception);
        }
    }

    @Override
    public ChatStreamSession stream(ChatModelRequest request, ChatStreamListener listener) {
        long startedAt = System.nanoTime();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        AtomicReference<String> responseModel = new AtomicReference<>(properties.model());
        AtomicReference<ModelUsage> usage = new AtomicReference<>(ModelUsage.unknown());
        AtomicLong firstTokenNanos = new AtomicLong(-1L);

        Disposable disposable;
        try {
            disposable = chatModel.stream(prompt(request)).subscribe(
                    response -> handleChunk(
                            response,
                            listener,
                            cancelled,
                            startedAt,
                            firstTokenNanos,
                            responseModel,
                            usage),
                    error -> {
                        if (!cancelled.get()) {
                            listener.onError(classify(error));
                        }
                    },
                    () -> {
                        if (!cancelled.get()) {
                            Duration timeToFirstToken = firstTokenNanos.get() < 0
                                    ? null
                                    : Duration.ofNanos(firstTokenNanos.get());
                            listener.onEvent(ChatStreamEvent.completed(
                                    PROVIDER,
                                    responseModel.get(),
                                    usage.get(),
                                    Duration.ofNanos(System.nanoTime() - startedAt),
                                    timeToFirstToken));
                        }
                    });
        }
        catch (RuntimeException exception) {
            throw classify(exception);
        }

        subscription.set(disposable);
        if (cancelled.get()) {
            disposable.dispose();
        }
        return new ChatStreamSession() {
            @Override
            public void cancel() {
                if (cancelled.compareAndSet(false, true)) {
                    Disposable active = subscription.get();
                    if (active != null) {
                        active.dispose();
                    }
                }
            }

            @Override
            public boolean cancelled() {
                return cancelled.get();
            }
        };
    }

    private Prompt prompt(ChatModelRequest request) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(properties.model())
                .temperature(request.temperature())
                .maxTokens(request.maxOutputTokens())
                .timeout(Duration.ofSeconds(properties.requestTimeoutSeconds()))
                .maxRetries(properties.maxRetries())
                .extraBody(Map.of(
                        "thinking",
                        Map.of("type", properties.thinkingEnabled() ? "enabled" : "disabled")))
                .build();
        return new Prompt(messages(request), options);
    }

    private static void handleChunk(
            ChatResponse response,
            ChatStreamListener listener,
            AtomicBoolean cancelled,
            long startedAt,
            AtomicLong firstTokenNanos,
            AtomicReference<String> responseModel,
            AtomicReference<ModelUsage> usage) {
        if (cancelled.get() || response == null) {
            return;
        }
        if (response.getMetadata() != null) {
            String model = response.getMetadata().getModel();
            if (model != null && !model.isBlank()) {
                responseModel.set(model);
            }
            Usage responseUsage = response.getMetadata().getUsage();
            if (responseUsage != null && responseUsage.getTotalTokens() != null
                    && responseUsage.getTotalTokens() > 0) {
                usage.set(toModelUsage(responseUsage));
            }
        }
        String content = response.getResult() == null ? null : response.getResult().getOutput().getText();
        if (content != null && !content.isEmpty()) {
            firstTokenNanos.compareAndSet(-1L, System.nanoTime() - startedAt);
            listener.onEvent(ChatStreamEvent.delta(content));
        }
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
            return new ModelCallException(ModelCallErrorCode.TEMPORARILY_UNAVAILABLE, PROVIDER, exception);
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

    private static List<Message> messages(ChatModelRequest request) {
        List<Message> messages = new ArrayList<>();
        if (!request.systemPrompt().isBlank()) {
            messages.add(new SystemMessage(request.systemPrompt()));
        }
        messages.add(new UserMessage(request.userPrompt()));
        return List.copyOf(messages);
    }

    private static ModelUsage toModelUsage(Usage usage) {
        if (usage == null) {
            return ModelUsage.unknown();
        }
        return new ModelUsage(
                usage.getPromptTokens(),
                usage.getCompletionTokens(),
                usage.getTotalTokens(),
                usage.getCacheReadInputTokens(),
                usage.getCacheWriteInputTokens());
    }
}
