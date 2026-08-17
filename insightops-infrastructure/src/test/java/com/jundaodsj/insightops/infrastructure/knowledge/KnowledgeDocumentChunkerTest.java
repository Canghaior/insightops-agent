package com.jundaodsj.insightops.infrastructure.knowledge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeDocumentChunkerTest {
    private final KnowledgeDocumentChunker chunker = new KnowledgeDocumentChunker();

    @Test
    void keepsHeadingOwnershipAndProducesStableHashes() {
        String first = "Spring AI provides portable model APIs. ".repeat(20);
        String second = "LangChain4j provides Java integrations. ".repeat(20);

        var chunks = chunker.chunk("# Spring AI\n\n" + first + "\n\n# LangChain4j\n\n" + second,
                100, 10);

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        assertThat(chunks.getFirst().headingPath()).isEqualTo("# Spring AI");
        assertThat(chunks.getLast().headingPath()).isEqualTo("# LangChain4j");
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.contentSha256()).hasSize(64);
            assertThat(chunk.estimatedTokens()).isPositive();
            assertThat(chunk.characterCount()).isEqualTo(chunk.content().length());
        });
        assertThat(chunker.chunk("# Spring AI\n\n" + first, 100, 10).getFirst().contentSha256())
                .isEqualTo(chunker.chunk("# Spring AI\n\n" + first, 100, 10).getFirst().contentSha256());
    }

    @Test
    void stripsNulCharactersAndIgnoresTinyFragments() {
        assertThat(chunker.chunk("tiny\u0000", 600, 80)).isEmpty();
    }
}
