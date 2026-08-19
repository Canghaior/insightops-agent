package com.jundaodsj.insightops.knowledge.application;

import java.util.UUID;

public class KnowledgeCollectionLeaseLostException extends RuntimeException {
    public KnowledgeCollectionLeaseLostException(UUID jobId) {
        super("Knowledge collection lease is no longer owned by job " + jobId);
    }
}
