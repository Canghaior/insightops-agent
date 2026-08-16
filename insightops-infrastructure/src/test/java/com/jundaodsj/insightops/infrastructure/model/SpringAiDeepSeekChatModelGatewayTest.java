package com.jundaodsj.insightops.infrastructure.model;

import com.jundaodsj.insightops.infrastructure.config.DeepSeekModelProperties;
import com.jundaodsj.insightops.model.application.ChatModelRequest;
import com.jundaodsj.insightops.model.application.ChatModelResponse;
import com.jundaodsj.insightops.model.application.ChatStreamEvent;
import com.jundaodsj.insightops.model.application.ChatStreamEventType;
import com.jundaodsj.insightops.model.application.ChatStreamListener;
import com.jundaodsj.insightops.model.application.ModelCallException;
import com.jundaodsj.insightops.model.application.ModelCallErrorCode;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiDeepSeekChatModelGatewayTest {

    @Test
    void shouldMapContentMetadataAndUsage() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse providerResponse = new ChatResponse(
                List.of(new Generation(new AssistantMessage("DEEPSEEK_SMOKE_OK"))),
                ChatResponseMetadata.builder()
                        .model("deepseek-v4-flash")
                        .usage(new DefaultUsage(8, 4, 12))
                        .build());
        when(chatModel.call(any(Prompt.class))).thenReturn(providerResponse);
        SpringAiDeepSeekChatModelGateway gateway = new SpringAiDeepSeekChatModelGateway(
                chatModel,
                new DeepSeekModelProperties(
                        true, "https://api.deepseek.com", "deepseek-v4-flash", false,
                        0.2, 4096, 4, 90, 2, false));

        ChatModelResponse response = gateway.generate(
                new ChatModelRequest("Reply exactly as requested.", "DEEPSEEK_SMOKE_OK", 0.0, 32));

        assertThat(response.content()).isEqualTo("DEEPSEEK_SMOKE_OK");
        assertThat(response.provider()).isEqualTo("deepseek");
        assertThat(response.model()).isEqualTo("deepseek-v4-flash");
        assertThat(response.usage().totalTokens()).isEqualTo(12);
        assertThat(response.duration()).isGreaterThanOrEqualTo(Duration.ZERO);
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        assertThat(promptCaptor.getValue().getOptions()).isInstanceOf(OpenAiChatOptions.class);
        OpenAiChatOptions options = (OpenAiChatOptions) promptCaptor.getValue().getOptions();
        assertThat(options.getExtraBody()).containsEntry("thinking", java.util.Map.of("type", "disabled"));
        assertThat(options.getMaxRetries()).isEqualTo(2);
        assertThat(options.getTimeout()).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void shouldStreamOrderedDeltasAndCompletionMetadata() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse first = new ChatResponse(List.of(new Generation(new AssistantMessage("Spring "))));
        ChatResponse second = new ChatResponse(
                List.of(new Generation(new AssistantMessage("AI"))),
                ChatResponseMetadata.builder()
                        .model("deepseek-v4-flash")
                        .usage(new DefaultUsage(12, 3, 15))
                        .build());
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(first, second));
        SpringAiDeepSeekChatModelGateway gateway = gateway(chatModel);
        List<ChatStreamEvent> events = new ArrayList<>();

        gateway.stream(new ChatModelRequest("", "question", 0.2, 128), listener(events));

        assertThat(events).extracting(ChatStreamEvent::type)
                .containsExactly(
                        ChatStreamEventType.CONTENT_DELTA,
                        ChatStreamEventType.CONTENT_DELTA,
                        ChatStreamEventType.COMPLETED);
        assertThat(events.get(0).content() + events.get(1).content()).isEqualTo("Spring AI");
        assertThat(events.get(2).usage().totalTokens()).isEqualTo(15);
        assertThat(events.get(2).timeToFirstToken()).isNotNull();
    }

    @Test
    void shouldStopDeliveringChunksAfterCancellation() {
        ChatModel chatModel = mock(ChatModel.class);
        Sinks.Many<ChatResponse> provider = Sinks.many().unicast().onBackpressureBuffer();
        when(chatModel.stream(any(Prompt.class))).thenReturn(provider.asFlux());
        SpringAiDeepSeekChatModelGateway gateway = gateway(chatModel);
        List<ChatStreamEvent> events = new ArrayList<>();
        var session = gateway.stream(
                new ChatModelRequest("", "question", 0.2, 128),
                listener(events));

        provider.tryEmitNext(new ChatResponse(List.of(new Generation(new AssistantMessage("first")))));
        session.cancel();
        provider.tryEmitNext(new ChatResponse(List.of(new Generation(new AssistantMessage("second")))));

        assertThat(session.cancelled()).isTrue();
        assertThat(events).extracting(ChatStreamEvent::content).containsExactly("first");
    }

    @Test
    void shouldMapIoFailureWithoutLeakingProviderMessage() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new OpenAIIoException("Authorization: Bearer secret-never-echo"));

        assertThatThrownBy(() -> gateway(chatModel).generate(
                new ChatModelRequest("", "question", 0.2, 128)))
                .isInstanceOf(ModelCallException.class)
                .hasMessage("Model call failed: TEMPORARILY_UNAVAILABLE")
                .hasMessageNotContaining("secret-never-echo");
    }

    @Test
    void shouldDistinguishAuthenticationFromRetryableStatus() {
        ChatModel chatModel = mock(ChatModel.class);
        OpenAIServiceException unauthorized = mock(OpenAIServiceException.class);
        when(unauthorized.statusCode()).thenReturn(401);
        when(chatModel.call(any(Prompt.class))).thenThrow(unauthorized);

        assertThatThrownBy(() -> gateway(chatModel).generate(
                new ChatModelRequest("", "question", 0.2, 128)))
                .isInstanceOfSatisfying(ModelCallException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ModelCallErrorCode.REQUEST_REJECTED));

        OpenAIServiceException rateLimited = mock(OpenAIServiceException.class);
        when(rateLimited.statusCode()).thenReturn(429);
        when(chatModel.call(any(Prompt.class))).thenThrow(rateLimited);
        assertThatThrownBy(() -> gateway(chatModel).generate(
                new ChatModelRequest("", "question", 0.2, 128)))
                .isInstanceOfSatisfying(ModelCallException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ModelCallErrorCode.TEMPORARILY_UNAVAILABLE));
    }

    private static SpringAiDeepSeekChatModelGateway gateway(ChatModel chatModel) {
        return new SpringAiDeepSeekChatModelGateway(
                chatModel,
                new DeepSeekModelProperties(
                        true, "https://api.deepseek.com", "deepseek-v4-flash", false,
                        0.2, 4096, 4, 90, 2, false));
    }

    private static ChatStreamListener listener(List<ChatStreamEvent> events) {
        return new ChatStreamListener() {
            @Override
            public void onEvent(ChatStreamEvent event) {
                events.add(event);
            }

            @Override
            public void onError(ModelCallException exception) {
                throw exception;
            }
        };
    }
}
