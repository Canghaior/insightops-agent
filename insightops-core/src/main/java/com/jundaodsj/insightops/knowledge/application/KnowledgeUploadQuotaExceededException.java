package com.jundaodsj.insightops.knowledge.application;

public class KnowledgeUploadQuotaExceededException extends RuntimeException {
    public KnowledgeUploadQuotaExceededException() {
        super("Workspace upload quota is exhausted");
    }
}
