package com.jundaodsj.insightops.infrastructure.config;

import com.jundaodsj.insightops.model.application.ResearchModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ResearchModelCatalog {
    private final DeepSeekModelProperties deepSeek;
    private final boolean openAiEnabled;
    private final Set<String> allowed;
    private final boolean deepSeekConfigured;

    public ResearchModelCatalog(DeepSeekModelProperties deepSeek,
            @Value("${insightops.model.openai.api-key:}") String openAiKey,
            @Value("${spring.ai.openai.api-key:}") String deepSeekKey,
            @Value("${insightops.model.openai.models:}") String allowedModels) {
        this.deepSeek = deepSeek;
        this.openAiEnabled = !openAiKey.isBlank();
        this.deepSeekConfigured = deepSeek.enabled() && !deepSeekKey.isBlank();
        this.allowed = Arrays.stream(allowedModels.split(",")).map(String::strip)
                .filter(id -> !id.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    public List<ResearchModel> available() {
        return Arrays.stream(ResearchModel.values()).filter(model -> "openai".equals(model.provider())
                ? openAiEnabled && (allowed.isEmpty() || allowed.contains(model.modelId())) : deepSeekConfigured).toList();
    }

    public ResearchModel resolve(String id) {
        if (id == null || id.isBlank()) {
            if (deepSeekConfigured || !openAiEnabled) return null;
            return available().stream().findFirst().orElseThrow(() -> new IllegalArgumentException("没有可用的研究模型。"));
        }
        var target = ResearchModel.fromId(id);
        if (!available().contains(target)) throw new IllegalArgumentException("这个模型尚未在服务端启用，请重新选择。");
        return target;
    }

    public String defaultLabel() {
        var model = resolve(null);
        if (model != null) return model.label();
        return deepSeekConfigured ? deepSeek.model() + (deepSeek.thinkingEnabled() ? " · 思考" : "") : "服务端默认";
    }
}
