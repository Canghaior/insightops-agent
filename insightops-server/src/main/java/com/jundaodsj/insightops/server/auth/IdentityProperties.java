package com.jundaodsj.insightops.server.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "insightops.identity")
public class IdentityProperties {
    private boolean enabled = true;
    private String publicBaseUrl = "http://localhost:5173";
    private int emailVerificationMinutes = 60;
    private int passwordResetMinutes = 30;
    private int invitationHours = 72;
    private int deletionGraceDays = 7;
    private final Mail mail = new Mail();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getPublicBaseUrl() { return publicBaseUrl; }
    public void setPublicBaseUrl(String value) { publicBaseUrl = value; }
    public int getEmailVerificationMinutes() { return emailVerificationMinutes; }
    public void setEmailVerificationMinutes(int value) { emailVerificationMinutes = value; }
    public int getPasswordResetMinutes() { return passwordResetMinutes; }
    public void setPasswordResetMinutes(int value) { passwordResetMinutes = value; }
    public int getInvitationHours() { return invitationHours; }
    public void setInvitationHours(int value) { invitationHours = value; }
    public int getDeletionGraceDays() { return deletionGraceDays; }
    public void setDeletionGraceDays(int value) { deletionGraceDays = value; }
    public Mail getMail() { return mail; }

    public static class Mail {
        private boolean enabled;
        private String from = "noreply@localhost";
        private int batchSize = 10;
        private int leaseSeconds = 120;
        private long initialDelayMs = 20_000;
        private long pollDelayMs = 15_000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getFrom() { return from; }
        public void setFrom(String value) { from = value; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int value) { batchSize = value; }
        public int getLeaseSeconds() { return leaseSeconds; }
        public void setLeaseSeconds(int value) { leaseSeconds = value; }
        public long getInitialDelayMs() { return initialDelayMs; }
        public void setInitialDelayMs(long value) { initialDelayMs = value; }
        public long getPollDelayMs() { return pollDelayMs; }
        public void setPollDelayMs(long value) { pollDelayMs = value; }
    }
}
