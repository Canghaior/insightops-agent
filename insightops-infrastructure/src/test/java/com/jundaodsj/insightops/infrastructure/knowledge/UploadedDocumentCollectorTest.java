package com.jundaodsj.insightops.infrastructure.knowledge;

import com.jundaodsj.insightops.knowledge.application.KnowledgeFileStorage;
import com.jundaodsj.insightops.knowledge.application.KnowledgeStore;
import com.jundaodsj.insightops.knowledge.application.OfficialDocumentGateway;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UploadedDocumentCollectorTest {

    @Test
    void collectsMarkdownAndReportsSafeDisplayProgress() throws Exception {
        KnowledgeFileStorage storage = mock(KnowledgeFileStorage.class);
        when(storage.open("upload.bin")).thenReturn(new ByteArrayInputStream("""
                # Architecture decision

                The team selected Spring AI with PostgreSQL pgvector for traceable retrieval evidence.
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        KnowledgeUploadProperties properties = new KnowledgeUploadProperties();
        var collector = new UploadedDocumentCollector(storage, new KnowledgeDocumentChunker(), properties);
        List<KnowledgeStore.CollectionProgress> progress = new ArrayList<>();

        var pages = collector.collect(source(), options(), progress::add);

        assertThat(pages).hasSize(1);
        assertThat(pages.getFirst().canonicalUrl()).startsWith("upload://");
        assertThat(pages.getFirst().chunks()).isNotEmpty();
        assertThat(progress.getLast().currentUrl()).isEqualTo("architecture.md");
        assertThat(progress.getLast().visitedUrlCount()).isEqualTo(1);
    }

    private static KnowledgeStore.SourceTask source() {
        UUID userId = UUID.randomUUID();
        return new KnowledgeStore.SourceTask(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "spring-ai", "upload-test", "architecture.md", "USER_UPLOAD",
                "upload://11111111-1111-1111-1111-111111111111/architecture.md",
                "upload://11111111-1111-1111-1111-111111111111/architecture.md",
                "uploads.internal", "/", "T2_USER_UPLOAD", 720, 0, null, null,
                "upload.bin", "architecture.md", "text/markdown", "PRIVATE", userId);
    }

    private static OfficialDocumentGateway.CrawlOptions options() {
        return new OfficialDocumentGateway.CrawlOptions(500, 0, 20 * 1024 * 1024,
                Duration.ofSeconds(5), Duration.ZERO, 600, 80);
    }
}
