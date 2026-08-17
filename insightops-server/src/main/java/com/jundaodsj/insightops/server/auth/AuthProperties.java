package com.jundaodsj.insightops.server.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "insightops.auth")
public class AuthProperties {

    private int sessionDays = 14;
    private boolean secureCookie;
    private int loginMaxFailures = 5;
    private int loginWindowMinutes = 15;
    private int loginLockMinutes = 15;
    private final Bootstrap bootstrap = new Bootstrap();

    public int getSessionDays() {
        return sessionDays;
    }

    public void setSessionDays(int sessionDays) {
        this.sessionDays = sessionDays;
    }

    public boolean isSecureCookie() {
        return secureCookie;
    }

    public void setSecureCookie(boolean secureCookie) {
        this.secureCookie = secureCookie;
    }

    public int getLoginMaxFailures() { return loginMaxFailures; }
    public void setLoginMaxFailures(int value) { loginMaxFailures = value; }
    public int getLoginWindowMinutes() { return loginWindowMinutes; }
    public void setLoginWindowMinutes(int value) { loginWindowMinutes = value; }
    public int getLoginLockMinutes() { return loginLockMinutes; }
    public void setLoginLockMinutes(int value) { loginLockMinutes = value; }

    public Bootstrap getBootstrap() {
        return bootstrap;
    }

    public static class Bootstrap {
        private boolean enabled;
        private String username = "alpha-owner";
        private String displayName = "Alpha Owner";
        private String password = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }
}
