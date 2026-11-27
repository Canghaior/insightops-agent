package com.jundaodsj.insightops.infrastructure.model;

import com.jundaodsj.insightops.infrastructure.config.DeepSeekModelProperties;
import com.jundaodsj.insightops.infrastructure.config.ResearchModelCatalog;
import com.jundaodsj.insightops.infrastructure.config.ResearchModelsEnabled;
import com.jundaodsj.insightops.model.application.AgentPlanningModelGateway;
import com.jundaodsj.insightops.model.application.ChatModelRequest;
import com.jundaodsj.insightops.model.application.ChatStreamListener;
import com.jundaodsj.insightops.model.application.ChatStreamSession;
import com.jundaodsj.insightops.model.application.ResearchModel;
import com.jundaodsj.insightops.model.application.StreamingChatModelGateway;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

@Component
@Primary
@Conditional(ResearchModelsEnabled.class)
public class ResearchModelGateways implements StreamingChatModelGateway, AgentPlanningModelGateway, AutoCloseable {
    private final Map<ResearchModel, Adapters> models = new EnumMap<>(ResearchModel.class);
    private final ObjectProvider<SpringAiDeepSeekChatModelGateway> defaultChat;
    private final ObjectProvider<SpringAiDeepSeekPlanningModelGateway> defaultPlanner;
    private final ResearchModelCatalog catalog;
    private final OpenAIClient openAiClient;

    public ResearchModelGateways(DeepSeekModelProperties settings, ResearchModelCatalog catalog,
            ObjectProvider<SpringAiDeepSeekChatModelGateway> defaultChat,
            ObjectProvider<SpringAiDeepSeekPlanningModelGateway> defaultPlanner,
            @Value("${insightops.model.openai.api-key:}") String openAiKey,
            @Value("${insightops.model.openai.base-url:https://api.openai.com/v1}") String openAiUrl,
            @Value("${spring.ai.openai.api-key:}") String deepSeekKey) {
        this.defaultChat = defaultChat;
        this.defaultPlanner = defaultPlanner;
        this.catalog = catalog;
        var timeout = Duration.ofSeconds(settings.requestTimeoutSeconds());
        this.openAiClient = openAiKey.isBlank() ? null : new OpenAIClientImpl(ClientOptions.builder()
                .httpClient(SpringAiOpenAiHttpClient.builder().timeout(timeout).build())
                .apiKey(openAiKey).baseUrl(openAiUrl).timeout(timeout).maxRetries(settings.maxRetries()).build());
        var responsesPlanner = openAiClient == null ? null : new OpenAiResponsesPlanningGateway(openAiClient);
        Map<String, ChatModel> clients = new HashMap<>();
        for (var choice : catalog.available()) {
            var client = clients.computeIfAbsent(choice.provider(), provider -> {
                boolean openAi = "openai".equals(provider);
                var builder = OpenAiChatModel.builder().options(OpenAiChatOptions.builder()
                        .baseUrl(openAi ? openAiUrl : settings.baseUrl()).apiKey(openAi ? openAiKey : deepSeekKey)
                        .model(choice.modelId()).maxRetries(settings.maxRetries())
                        .timeout(timeout).build());
                if (openAi) builder.openAiClient(openAiClient).openAiClientAsync(openAiClient.async());
                return builder.build();
            });
            models.put(choice, new Adapters(new SpringAiDeepSeekChatModelGateway(client, settings, choice),
                    "openai".equals(choice.provider()) ? responsesPlanner
                            : new SpringAiDeepSeekPlanningModelGateway(client, settings, choice)));
        }
    }

    @Override
    public ChatStreamSession stream(ChatModelRequest request, ChatStreamListener listener) {
        var choice = request.researchModel();
        var chat = choice == null ? defaultChat.getObject() : models.get(catalog.resolve(choice.id())).chat();
        return chat.stream(request, listener);
    }

    @Override
    public AgentPlanResponse plan(AgentPlanRequest request) {
        var choice = request.researchModel();
        var planner = choice == null ? defaultPlanner.getObject() : models.get(catalog.resolve(choice.id())).planner();
        return planner.plan(request);
    }

    @Override
    public void close() {
        if (openAiClient != null) openAiClient.close();
    }

    private record Adapters(StreamingChatModelGateway chat, AgentPlanningModelGateway planner) { }
}
