package com.jundaodsj.insightops.server.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "insightops.knowledge.rag")
public class KnowledgeRagProperties {
    private boolean enabled;
    private int candidateLimit = 12;
    private int maxEvidenceChunks = 6;
    private int maxChunksPerDocument = 2;
    private int maxContextCharacters = 12_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getCandidateLimit() { return candidateLimit; }
    public void setCandidateLimit(int candidateLimit) { this.candidateLimit = candidateLimit; }
    public int getMaxEvidenceChunks() { return maxEvidenceChunks; }
    public void setMaxEvidenceChunks(int maxEvidenceChunks) { this.maxEvidenceChunks = maxEvidenceChunks; }
    public int getMaxChunksPerDocument() { return maxChunksPerDocument; }
    public void setMaxChunksPerDocument(int maxChunksPerDocument) {
        this.maxChunksPerDocument = maxChunksPerDocument;
    }
    public int getMaxContextCharacters() { return maxContextCharacters; }
    public void setMaxContextCharacters(int maxContextCharacters) {
        this.maxContextCharacters = maxContextCharacters;
    }
}
