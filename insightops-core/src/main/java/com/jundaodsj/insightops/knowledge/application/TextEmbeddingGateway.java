package com.jundaodsj.insightops.knowledge.application;

import java.util.List;

public interface TextEmbeddingGateway {

    List<float[]> embed(List<String> texts);
}
