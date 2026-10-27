package com.jundaodsj.insightops.server.tool;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "insightops.tool.github-mcp")
public class GitHubMcpProperties {
    private boolean enabled;
    private String token = "";
    private int requestTimeoutSeconds = 15;
    private int maxBodyChars = 12_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token == null ? "" : token.trim(); }
    public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
    public void setRequestTimeoutSeconds(int seconds) { this.requestTimeoutSeconds = seconds; }
    public int getMaxBodyChars() { return maxBodyChars; }
    public void setMaxBodyChars(int maxBodyChars) { this.maxBodyChars = maxBodyChars; }
}
