package com.jundaodsj.insightops.server.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.infrastructure.identity.IdentitySecretCipher;
import com.jundaodsj.insightops.infrastructure.identity.PersonalDataExportRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class PersonalDataExportService {
    private final PersonalDataExportRepository repository;
    private final PersonalDataExportStorage storage;
    private final PersonalDataExportProperties properties;
    private final IdentitySecretCipher cipher;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock = Clock.systemUTC();

    public PersonalDataExportService(PersonalDataExportRepository repository,
                                     PersonalDataExportStorage storage,
                                     PersonalDataExportProperties properties,
                                     IdentitySecretCipher cipher, ObjectMapper json) {
        this.repository = repository; this.storage = storage; this.properties = properties;
        this.cipher = cipher; this.json = json;
    }

    public ExportCreated create(UUID userId) {
        String storageKey = null;
        try {
            Instant now = clock.instant();
            UUID id = UUID.randomUUID();
            byte[] tokenBytes = new byte[32]; random.nextBytes(tokenBytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
            Instant expiresAt = now.plus(Math.max(1, properties.getExpiresHours()), ChronoUnit.HOURS);
            PersonalDataExportRepository.Snapshot snapshot = repository.snapshot(userId);
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("format", "insightops-personal-data-export-v1");
            document.put("generatedAt", now);
            document.put("account", json.readTree(snapshot.account()));
            document.put("workspaces", json.readTree(snapshot.workspaces()));
            document.put("conversations", json.readTree(snapshot.conversations()));
            document.put("messages", json.readTree(snapshot.messages()));
            document.put("agentRuns", json.readTree(snapshot.runs()));
            document.put("memories", json.readTree(snapshot.memories()));
            document.put("uploads", json.readTree(snapshot.uploads()));
            document.put("legalConsents", json.readTree(snapshot.legalConsents()));
            storageKey = id + ".json.enc";
            storage.write(storageKey, cipher.encrypt(json.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(document)));
            repository.createReady(id, userId, storageKey, AuthService.hash(token), expiresAt, now);
            return new ExportCreated(id, token, expiresAt);
        } catch (RuntimeException exception) {
            cleanupFailedWrite(storageKey, exception);
            throw exception;
        } catch (Exception exception) {
            IllegalStateException failure = new IllegalStateException(
                    "Unable to create personal data export", exception);
            cleanupFailedWrite(storageKey, failure);
            throw failure;
        }
    }

    public byte[] download(UUID userId, UUID exportId, String token) {
        if (token == null || token.length() < 32 || token.length() > 200) throw invalid();
        String key = repository.consume(exportId, userId, AuthService.hash(token), clock.instant())
                .orElseThrow(PersonalDataExportService::invalid);
        return cipher.decrypt(storage.read(key)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public void expire() {
        Instant now = clock.instant();
        repository.findExpired(now, 100).forEach(value -> {
            storage.delete(value.storageKey());
            repository.markExpired(value.id(), now);
        });
    }
    private void cleanupFailedWrite(String storageKey, RuntimeException failure) {
        if (storageKey == null) return;
        try { storage.delete(storageKey); }
        catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
    }
    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Export link is invalid, expired or already used");
    }
    public record ExportCreated(UUID exportId, String downloadToken, Instant expiresAt) { }
}
