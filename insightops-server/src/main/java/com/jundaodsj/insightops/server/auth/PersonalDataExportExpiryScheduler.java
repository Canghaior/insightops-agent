package com.jundaodsj.insightops.server.auth;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PersonalDataExportExpiryScheduler {
    private final PersonalDataExportService service;
    public PersonalDataExportExpiryScheduler(PersonalDataExportService service) { this.service = service; }
    @Scheduled(initialDelayString = "${insightops.identity.export.expiry-initial-delay-ms:60000}",
            fixedDelayString = "${insightops.identity.export.expiry-poll-delay-ms:3600000}")
    public void expire() { service.expire(); }
}
