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
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

@Component
@Primary
@Conditional(ResearchModelsEnabled.class)
public class ResearchModelGateways implements StreamingChatModelGateway, AgentPlanningModelGateway {
    private final Map<ResearchModel, Adapters> models = new EnumMap<>(ResearchModel.class);
    private final ObjectProvider<SpringAiDeepSeekChatModelGateway> defaultChat;
    private final ObjectProvider<SpringAiDeepSeekPlanningModelGateway> defaultPlanner;
    private final ResearchModelCatalog catalog;

    public ResearchModelGateways(DeepSeekModelProperties settings, ResearchModelCatalog catalog,
            ObjectProvider<SpringAiDeepSeekChatModelGateway> defaultChat,
            ObjectProvider<SpringAiDeepSeekPlanningModelGateway> defaultPlanner,
            @Value("${insightops.model.openai.api-key:}") String openAiKey,
            @Value("${insightops.model.openai.base-url:https://api.openai.com/v1}") String openAiUrl,
            @Value("${spring.ai.openai.api-key:}") String deepSeekKey) {
        this.defaultChat = defaultChat;
        this.defaultPlanner = defaultPlanner;
        this.catalog = catalog;
        Map<String, ChatModel> clients = new HashMap<>();
        for (var choice : catalog.available()) {
            var client = clients.computeIfAbsent(choice.provider(), provider -> {
                boolean openAi = "openai".equals(provider);
                return OpenAiChatModel.builder().options(OpenAiChatOptions.builder()
                        .baseUrl(openAi ? openAiUrl : settings.baseUrl()).apiKey(openAi ? openAiKey : deepSeekKey)
                        .model(choice.modelId()).maxRetries(settings.maxRetries())
                        .timeout(java.time.Duration.ofSeconds(settings.requestTimeoutSeconds())).build()).build();
            });
            models.put(choice, new Adapters(new SpringAiDeepSeekChatModelGateway(client, settings, choice),
                    new SpringAiDeepSeekPlanningModelGateway(client, settings, choice)));
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

    private record Adapters(StreamingChatModelGateway chat, AgentPlanningModelGateway planner) { }
}
