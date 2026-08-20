package com.jundaodsj.insightops.infrastructure.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "insightops.knowledge.upload")
public class KnowledgeUploadProperties {
    private String directory = "./data/knowledge-uploads";
    private long maxFileBytes = 20L * 1024 * 1024;
    private long workspaceQuotaBytes = 1024L * 1024 * 1024;
    private int maxPdfPages = 500;
    private int maxExtractedCharacters = 2_000_000;

    public String getDirectory() { return directory; }
    public void setDirectory(String directory) { this.directory = directory; }
    public long getMaxFileBytes() { return maxFileBytes; }
    public void setMaxFileBytes(long maxFileBytes) { this.maxFileBytes = maxFileBytes; }
    public long getWorkspaceQuotaBytes() { return workspaceQuotaBytes; }
    public void setWorkspaceQuotaBytes(long workspaceQuotaBytes) { this.workspaceQuotaBytes = workspaceQuotaBytes; }
    public int getMaxPdfPages() { return maxPdfPages; }
    public void setMaxPdfPages(int maxPdfPages) { this.maxPdfPages = maxPdfPages; }
    public int getMaxExtractedCharacters() { return maxExtractedCharacters; }
    public void setMaxExtractedCharacters(int maxExtractedCharacters) { this.maxExtractedCharacters = maxExtractedCharacters; }
}
