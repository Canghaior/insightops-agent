package com.jundaodsj.insightops.infrastructure.model;

import com.jundaodsj.insightops.infrastructure.config.DeepSeekModelProperties;
import com.jundaodsj.insightops.model.application.AgentPlanningModelGateway;
import com.jundaodsj.insightops.model.application.ModelUsage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiDeepSeekPlanningModelGatewayTest {

    @Test
    void shouldExposeNativeFunctionCallWithoutExecutingCallback() {
        ChatModel model = mock(ChatModel.class);
        AssistantMessage output = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "knowledge_hybrid_search",
                        "{\"query\":\"Spring AI\"}")))
                .build();
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(output)),
                ChatResponseMetadata.builder()
                        .model("deepseek-v4-flash")
                        .usage(new DefaultUsage(12, 4, 16))
                        .build()));
        SpringAiDeepSeekPlanningModelGateway gateway = gateway(model);

        var result = gateway.plan(request(List.of()));

        assertThat(result.toolCalls()).containsExactly(new AgentPlanningModelGateway.PlannedToolCall(
                "call-1", "knowledge_hybrid_search", "{\"query\":\"Spring AI\"}"));
        assertThat(result.usage()).isEqualTo(new ModelUsage(12, 4, 16, null, null));
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getOptions()).isInstanceOf(OpenAiChatOptions.class);
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getToolChoice()).isEqualTo("auto");
        assertThat(options.getParallelToolCalls()).isFalse();
        assertThat(options.getToolCallbacks()).hasSize(1);
    }

    @Test
    void shouldReplayAssistantToolCallAndObservationIntoNextPlanningTurn() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("FINISH")))));
        var previous = new AgentPlanningModelGateway.PlannedToolCall(
                "call-previous", "knowledge_hybrid_search", "{\"query\":\"docs\"}");
        SpringAiDeepSeekPlanningModelGateway gateway = gateway(model);

        var result = gateway.plan(request(List.of(
                new AgentPlanningModelGateway.ToolExchange(previous, "{\"resultCount\":2}"))));

        assertThat(result.content()).isEqualTo("FINISH");
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions()).hasSize(4);
        assertThat(prompt.getValue().getInstructions().get(2)).isInstanceOf(AssistantMessage.class);
        assertThat(((AssistantMessage) prompt.getValue().getInstructions().get(2)).getToolCalls())
                .extracting(AssistantMessage.ToolCall::id)
                .containsExactly("call-previous");
    }

    private static AgentPlanningModelGateway.AgentPlanRequest request(
            List<AgentPlanningModelGateway.ToolExchange> exchanges) {
        return new AgentPlanningModelGateway.AgentPlanRequest(
                "Plan safely", "What changed?", exchanges,
                List.of(new AgentPlanningModelGateway.FunctionDefinition(
                        "knowledge_hybrid_search", "Search official knowledge",
                        "{\"type\":\"object\",\"properties\":{}}")),
                0.0, 512);
    }

    private static SpringAiDeepSeekPlanningModelGateway gateway(ChatModel model) {
        return new SpringAiDeepSeekPlanningModelGateway(
                model,
                new DeepSeekModelProperties(
                        true, "https://api.deepseek.com", "deepseek-v4-flash", false,
                        0.2, 4096, 4, 90, 2, false));
    }
}
