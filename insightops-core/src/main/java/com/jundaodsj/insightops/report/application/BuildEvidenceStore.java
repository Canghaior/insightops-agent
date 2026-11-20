package com.jundaodsj.insightops.report.application;

import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.report.application.BuildEvidence.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BuildEvidenceStore {
    record Receipt(UUID id, String fingerprint) {}

    List<Summary> list(ActorContext actor, UUID systemId);
    Optional<Batch> find(ActorContext actor, UUID systemId, UUID batchId);
    Optional<Original> original(ActorContext actor, UUID systemId, UUID batchId, UUID fileId);
    Optional<Receipt> request(ActorContext actor, UUID systemId, UUID requestId);

    /** Atomically inserts the header and all originals. Returns false if another request won the same key. */
    boolean insert(ActorContext actor, UUID requestId, String fingerprint, Batch batch, List<Original> originals);
}
