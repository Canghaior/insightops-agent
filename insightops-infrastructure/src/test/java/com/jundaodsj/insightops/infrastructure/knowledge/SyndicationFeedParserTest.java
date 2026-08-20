package com.jundaodsj.insightops.infrastructure.knowledge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyndicationFeedParserTest {
    private final SyndicationFeedParser parser = new SyndicationFeedParser();

    @Test
    void parsesRssAndStripsEmbeddedHtml() {
        var items = parser.parse("""
                <?xml version="1.0"?><rss version="2.0"><channel><item>
                <guid>spring-ai-1</guid><title>Spring AI update</title>
                <link>https://spring.io/blog/2026/spring-ai</link>
                <pubDate>Tue, 18 Aug 2026 10:00:00 GMT</pubDate>
                <description><![CDATA[<p>New <strong>agent</strong> support.</p>]]></description>
                </item></channel></rss>
                """, "https://spring.io/blog.atom", 10);

        assertThat(items).hasSize(1);
        assertThat(items.getFirst().link()).isEqualTo("https://spring.io/blog/2026/spring-ai");
        assertThat(items.getFirst().content()).contains("# Spring AI update", "New agent support");
    }

    @Test
    void parsesAtomAlternateLinkAndHonorsLimit() {
        var items = parser.parse("""
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <entry><id>a</id><title>A</title><link rel="alternate" href="https://example.com/a"/><summary>Alpha content long enough.</summary></entry>
                  <entry><id>b</id><title>B</title><link href="https://example.com/b"/><summary>Beta content long enough.</summary></entry>
                </feed>
                """, "https://example.com/feed.xml", 1);

        assertThat(items).hasSize(1);
        assertThat(items.getFirst().externalId()).isEqualTo("a");
    }

    @Test
    void rejectsDocumentsWithoutEntries() {
        assertThatThrownBy(() -> parser.parse("<rss><channel/></rss>", "https://example.com/feed", 10))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
