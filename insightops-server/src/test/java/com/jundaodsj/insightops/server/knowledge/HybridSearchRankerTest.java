package com.jundaodsj.insightops.server.knowledge;

import com.jundaodsj.insightops.knowledge.application.KnowledgeEmbeddingStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HybridSearchRankerTest {

    private final HybridSearchRanker ranker = new HybridSearchRanker();

    @Test
    void ranksAResultSeenByBothChannelsAheadOfSingleChannelResults() {
        var shared = result("Spring AI", "shared");
        var semanticOnly = result("LangChain4j", "semantic");
        var keywordOnly = result("Dify", "keyword");

        var ranked = ranker.fuse("embedding model",
                List.of(semanticOnly, shared), List.of(keywordOnly, shared), 3);

        assertThat(ranked.getFirst().chunkId()).isEqualTo(shared.chunkId());
        assertThat(ranked).extracting(KnowledgeEmbeddingStore.SearchResult::score)
                .allMatch(score -> score > 0.0 && score <= 1.0);
    }

    @Test
    void appliesAnExplicitProjectBoostWithoutDroppingOtherCandidates() {
        var spring = result("spring-ai", "spring");
        var dify = result("dify", "dify");

        var ranked = ranker.fuse("Spring AI advisor",
                List.of(dify, spring), List.of(), 2);

        assertThat(ranked).extracting(KnowledgeEmbeddingStore.SearchResult::projectName)
                .containsExactly("spring-ai", "dify");
    }

    private static KnowledgeEmbeddingStore.SearchResult result(String project, String slug) {
        return new KnowledgeEmbeddingStore.SearchResult(
                UUID.nameUUIDFromBytes(slug.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                UUID.randomUUID(), project, project + " docs", project + " title",
                "https://docs.example.test/" + slug, "heading", "content", "en",
                "T1_PROJECT_DOMAIN", 0.8);
    }

    @Test
    void prefersTheExactVersionChangelogWithoutConfusingPatchPrefixes() {
        var target = versioned("3.5.1");
        var later = versioned("3.5.10");
        var older = versioned("3.5.0");
        var ranked = ranker.fuse("Vue 3.5.1 changelog fixes", List.of(later, older, target), List.of(), 3);
        assertThat(ranked.getFirst().versionLabel()).isEqualTo("3.5.1");
        assertThat(ranked).hasSize(3);
    }

    private static KnowledgeEmbeddingStore.SearchResult versioned(String version) {
        return new KnowledgeEmbeddingStore.SearchResult(UUID.randomUUID(), UUID.randomUUID(), "core", "Vue CHANGELOG",
                "Vue " + version, "https://raw.githubusercontent.com/vuejs/core/v" + version + "/CHANGELOG.md", "Bug Fixes",
                "release changes", "en", "T1_PROJECT_DOMAIN", 0.8, version, java.time.Instant.now());
    }
}
