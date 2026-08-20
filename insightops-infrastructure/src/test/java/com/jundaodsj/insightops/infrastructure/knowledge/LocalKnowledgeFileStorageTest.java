package com.jundaodsj.insightops.infrastructure.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalKnowledgeFileStorageTest {
    @TempDir Path directory;

    @Test
    void storesHashesReadsAndDeletesInsideConfiguredRoot() throws Exception {
        KnowledgeUploadProperties properties = new KnowledgeUploadProperties();
        properties.setDirectory(directory.toString());
        var storage = new LocalKnowledgeFileStorage(properties);
        UUID id = UUID.randomUUID();

        var stored = storage.store(id, new ByteArrayInputStream("safe content".getBytes(StandardCharsets.UTF_8)), 100);

        assertThat(stored.storageKey()).isEqualTo(id + ".bin");
        assertThat(stored.byteSize()).isEqualTo(12);
        assertThat(new String(storage.open(stored.storageKey()).readAllBytes(), StandardCharsets.UTF_8))
                .isEqualTo("safe content");
        storage.delete(stored.storageKey());
        assertThatThrownBy(() -> storage.open(stored.storageKey())).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> storage.open("../secret")).isInstanceOf(IllegalArgumentException.class);
    }
}
