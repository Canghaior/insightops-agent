package com.jundaodsj.insightops.server.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "insightops.tencent-ses")
public class TencentSesProperties {
    private boolean enabled;
    private String secretId = "";
    private String secretKey = "";
    private String region = "ap-guangzhou";
    private String endpoint = "https://ses.tencentcloudapi.com";
    private String fromAddress = "no-reply@mail.canghaior.com";
    private String fromName = "InsightOps Agent";
    private String replyTo = "";
    private long emailVerificationTemplateId = 58078;
    private long passwordResetTemplateId = 58079;
    private long workspaceInvitationTemplateId = 58080;
    private int timeoutSeconds = 10;

    public boolean isReady() {
        return enabled && present(secretId) && present(secretKey) && present(region)
                && present(endpoint) && present(fromAddress)
                && emailVerificationTemplateId > 0 && passwordResetTemplateId > 0
                && workspaceInvitationTemplateId > 0;
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getSecretId() { return secretId; }
    public void setSecretId(String value) { secretId = value; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String value) { secretKey = value; }
    public String getRegion() { return region; }
    public void setRegion(String value) { region = value; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String value) { endpoint = value; }
    public String getFromAddress() { return fromAddress; }
    public void setFromAddress(String value) { fromAddress = value; }
    public String getFromName() { return fromName; }
    public void setFromName(String value) { fromName = value; }
    public String getReplyTo() { return replyTo; }
    public void setReplyTo(String value) { replyTo = value; }
    public long getEmailVerificationTemplateId() { return emailVerificationTemplateId; }
    public void setEmailVerificationTemplateId(long value) { emailVerificationTemplateId = value; }
    public long getPasswordResetTemplateId() { return passwordResetTemplateId; }
    public void setPasswordResetTemplateId(long value) { passwordResetTemplateId = value; }
    public long getWorkspaceInvitationTemplateId() { return workspaceInvitationTemplateId; }
    public void setWorkspaceInvitationTemplateId(long value) { workspaceInvitationTemplateId = value; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int value) { timeoutSeconds = value; }

    private static boolean present(String value) { return value != null && !value.isBlank(); }
}
