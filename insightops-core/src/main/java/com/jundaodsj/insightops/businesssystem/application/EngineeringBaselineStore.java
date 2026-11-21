package com.jundaodsj.insightops.businesssystem.application;

import com.jundaodsj.insightops.identity.application.ActorContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EngineeringBaselineStore {
    enum Kind { DECLARATION, STATIC_DERIVATION, MAVEN_RESOLUTION, BUILD_ENVIRONMENT, RUNTIME_OBSERVATION }
    record Source(String path, int line) {}
    record Fact(Kind kind, String subject, String value, Source source, List<Source> via) {}
    record FileInfo(String path, String sha256, int size) {}
    record Analysis(String sourceLabel, List<FileInfo> files, List<Fact> facts, List<String> gaps) {}
    record Profile(int revision, String technologyStack) {}
    record Content(String label, String note, String expectedJava, Profile profileAtImport, Analysis evidence,
                   List<String> conflicts, String originalName, String originalSha256) {}
    record Summary(UUID id, UUID systemId, int version, String label, Instant savedAt) {}
    record Snapshot(Summary baseline, Content content) {}
    record Receipt(UUID id, String requestHash) {}
    Optional<Profile> profile(ActorContext actor, UUID system, boolean lock);
    List<Summary> list(ActorContext actor, UUID system);
    Optional<Snapshot> find(ActorContext actor, UUID system, UUID id);
    Optional<byte[]> original(ActorContext actor, UUID system, UUID id);
    Optional<Receipt> request(ActorContext actor, UUID system, UUID requestId);
    Snapshot append(ActorContext actor, UUID system, UUID requestId, String requestHash, Content content, byte[] material);
}

