package com.jundaodsj.insightops.knowledge.application;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

public interface KnowledgeFileStorage {

    StoredFile store(UUID uploadId, InputStream input, long maximumBytes) throws IOException;

    InputStream open(String storageKey) throws IOException;

    void delete(String storageKey) throws IOException;

    record StoredFile(String storageKey, long byteSize, String sha256) { }
}
