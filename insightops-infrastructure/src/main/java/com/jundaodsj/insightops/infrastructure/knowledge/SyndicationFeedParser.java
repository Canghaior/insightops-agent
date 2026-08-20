package com.jundaodsj.insightops.infrastructure.knowledge;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class SyndicationFeedParser {

    List<FeedItem> parse(String body, String baseUrl, int limit) {
        Document xml = Jsoup.parse(body == null ? "" : body, baseUrl, Parser.xmlParser());
        List<Element> entries = new ArrayList<>(xml.select("item"));
        if (entries.isEmpty()) entries.addAll(xml.select("feed > entry"));
        if (entries.isEmpty()) throw new IllegalArgumentException("Feed contains no RSS or Atom entries");
        List<FeedItem> result = new ArrayList<>();
        for (Element entry : entries) {
            if (result.size() >= Math.max(1, limit)) break;
            String title = text(entry, "title").orElse("Untitled official update");
            String link = atomLink(entry).or(() -> text(entry, "link")).orElse("");
            String id = text(entry, "guid").or(() -> text(entry, "id")).orElse(link);
            String published = text(entry, "published").or(() -> text(entry, "pubDate"))
                    .or(() -> text(entry, "updated")).orElse("");
            String rawContent = text(entry, "content:encoded").or(() -> text(entry, "content"))
                    .or(() -> text(entry, "description")).or(() -> text(entry, "summary")).orElse("");
            String readable = Jsoup.parseBodyFragment(rawContent).text().strip();
            StringBuilder content = new StringBuilder("# ").append(title).append("\n\n");
            if (!published.isBlank()) content.append("Published: ").append(published).append("\n\n");
            if (!readable.isBlank()) content.append(readable);
            if (readable.isBlank()) content.append("Official update: ").append(title);
            result.add(new FeedItem(id, link, title, published, content.toString().strip()));
        }
        return List.copyOf(result);
    }

    private static Optional<String> atomLink(Element entry) {
        Element alternate = entry.selectFirst("link[rel=alternate][href]");
        if (alternate == null) alternate = entry.selectFirst("link[href]");
        return alternate == null ? Optional.empty() : nonBlank(alternate.attr("href"));
    }

    private static Optional<String> text(Element entry, String tag) {
        Element value = entry.getElementsByTag(tag).stream().findFirst().orElse(null);
        return value == null ? Optional.empty() : nonBlank(value.text());
    }

    private static Optional<String> nonBlank(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.strip());
    }

    record FeedItem(String externalId, String link, String title, String published, String content) { }
}
