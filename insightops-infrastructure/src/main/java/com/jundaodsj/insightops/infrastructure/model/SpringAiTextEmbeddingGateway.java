package com.jundaodsj.insightops.infrastructure.model;

import com.jundaodsj.insightops.knowledge.application.TextEmbeddingGateway;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SpringAiTextEmbeddingGateway implements TextEmbeddingGateway {
    private final ObjectProvider<EmbeddingModel> models;

    public SpringAiTextEmbeddingGateway(ObjectProvider<EmbeddingModel> models) {
        this.models = models;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        EmbeddingModel model = models.getIfAvailable();
        if (model == null) {
            throw new IllegalStateException("Embedding model is disabled or unavailable");
        }
        return model.embed(texts);
    }
}
