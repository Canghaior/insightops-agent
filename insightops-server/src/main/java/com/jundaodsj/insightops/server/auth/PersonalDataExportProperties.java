package com.jundaodsj.insightops.server.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "insightops.identity.export")
public class PersonalDataExportProperties {
    private String directory = "./data/personal-data-exports";
    private int expiresHours = 24;
    public String getDirectory() { return directory; }
    public void setDirectory(String value) { directory = value; }
    public int getExpiresHours() { return expiresHours; }
    public void setExpiresHours(int value) { expiresHours = value; }
}
