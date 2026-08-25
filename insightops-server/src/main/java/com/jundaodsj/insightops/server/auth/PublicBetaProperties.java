package com.jundaodsj.insightops.server.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "insightops.public-beta")
public class PublicBetaProperties {
    private boolean enabled;
    private int maximumRegistrations = 100;
    private int minimumAge = 14;
    private int pendingVerificationHours = 24;
    private String operatorName = "";
    private String contactEmail = "";
    private String termsVersion = "2026-08-26";
    private String privacyVersion = "2026-08-26";
    private String acceptableUseVersion = "2026-08-26";
    private final Turnstile turnstile = new Turnstile();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public int getMaximumRegistrations() { return maximumRegistrations; }
    public void setMaximumRegistrations(int value) { maximumRegistrations = value; }
    public int getMinimumAge() { return minimumAge; }
    public void setMinimumAge(int value) { minimumAge = value; }
    public int getPendingVerificationHours() { return pendingVerificationHours; }
    public void setPendingVerificationHours(int value) { pendingVerificationHours = value; }
    public String getOperatorName() { return operatorName; }
    public void setOperatorName(String value) { operatorName = value; }
    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String value) { contactEmail = value; }
    public String getTermsVersion() { return termsVersion; }
    public void setTermsVersion(String value) { termsVersion = value; }
    public String getPrivacyVersion() { return privacyVersion; }
    public void setPrivacyVersion(String value) { privacyVersion = value; }
    public String getAcceptableUseVersion() { return acceptableUseVersion; }
    public void setAcceptableUseVersion(String value) { acceptableUseVersion = value; }
    public Turnstile getTurnstile() { return turnstile; }

    public static class Turnstile {
        private boolean enabled;
        private String siteKey = "";
        private String secretKey = "";
        private String expectedHostname = "insightops.canghaior.com";
        private String expectedAction = "register";
        private String verifyUrl = "https://challenges.cloudflare.com/turnstile/v0/siteverify";
        private int timeoutSeconds = 8;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { enabled = value; }
        public String getSiteKey() { return siteKey; }
        public void setSiteKey(String value) { siteKey = value; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String value) { secretKey = value; }
        public String getExpectedHostname() { return expectedHostname; }
        public void setExpectedHostname(String value) { expectedHostname = value; }
        public String getExpectedAction() { return expectedAction; }
        public void setExpectedAction(String value) { expectedAction = value; }
        public String getVerifyUrl() { return verifyUrl; }
        public void setVerifyUrl(String value) { verifyUrl = value; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int value) { timeoutSeconds = value; }
    }
}
