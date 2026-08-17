package com.jundaodsj.insightops.server.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "insightops.knowledge.embedding")
public class KnowledgeEmbeddingProperties {
    private boolean enabled;
    private String provider = "ollama";
    private String model = "bge-m3";
    private int dimensions = 1024;
    private double minimumScore = 0.35;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getDimensions() { return dimensions; }
    public void setDimensions(int dimensions) { this.dimensions = dimensions; }
    public double getMinimumScore() { return minimumScore; }
    public void setMinimumScore(double minimumScore) { this.minimumScore = minimumScore; }
}
