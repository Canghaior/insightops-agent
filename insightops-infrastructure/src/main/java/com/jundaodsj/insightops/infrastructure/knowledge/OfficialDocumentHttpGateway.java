package com.jundaodsj.insightops.infrastructure.knowledge;

import com.jundaodsj.insightops.knowledge.application.DocumentCollectionException;
import com.jundaodsj.insightops.knowledge.application.KnowledgeStore;
import com.jundaodsj.insightops.knowledge.application.OfficialDocumentGateway;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class OfficialDocumentHttpGateway implements OfficialDocumentGateway {
    private static final String USER_AGENT = "InsightOpsAgent/0.1 (+official-document-collector)";
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[[^]]*]\\((https://[^)]+)\\)");
    private static final Pattern VERSION_PATH = Pattern.compile("/(\\d+\\.\\d+(?:\\.\\d+)?(?:-SNAPSHOT)?)/", Pattern.CASE_INSENSITIVE);
    private static final Set<String> SKIPPED_SUFFIXES = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico", ".pdf", ".zip", ".gz",
            ".css", ".js", ".map", ".woff", ".woff2", ".ttf", ".xml", ".json");

    private final HttpClient http;
    private final KnowledgeDocumentChunker chunker;

    @Autowired
    public OfficialDocumentHttpGateway(KnowledgeDocumentChunker chunker) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build(), chunker);
    }

    OfficialDocumentHttpGateway(HttpClient http, KnowledgeDocumentChunker chunker) {
        this.http = http;
        this.chunker = chunker;
    }

    @Override
    public List<KnowledgeStore.DocumentPage> collect(KnowledgeStore.SourceTask source, CrawlOptions options,
                                                     ProgressListener progressListener) {
        URI discovery = validate(source.discoveryUrl(), source, true);
        int maxPages = Math.max(1, options.maxPages());
        report(progressListener, maxPages, 0, 0, 0, discovery.toString());
        RobotsRules robots = readRobots(source, options);
        ArrayDeque<Candidate> queue = new ArrayDeque<>();
        Set<String> queued = new HashSet<>();
        Set<String> visited = new HashSet<>();

        if (discovery.getPath().endsWith("llms.txt")) {
            FetchResult index = fetch(discovery, source, options, true);
            for (String link : markdownLinks(index.body())) enqueue(queue, queued, source, link, 0, robots);
            enqueue(queue, queued, source, source.rootUrl(), 0, robots);
        } else if (discovery.getPath().endsWith("sitemap.xml")) {
            FetchResult index = fetch(discovery, source, options, true);
            Document xml = Jsoup.parse(index.body(), source.discoveryUrl(), Parser.xmlParser());
            for (Element location : xml.select("url > loc")) {
                enqueue(queue, queued, source, location.text(), 0, robots);
            }
        } else {
            enqueue(queue, queued, source, discovery.toString(), 0, robots);
        }
        report(progressListener, maxPages, queued.size(), 0, 0, discovery.toString());

        List<KnowledgeStore.DocumentPage> pages = new ArrayList<>();
        long lastRequestAt = 0;
        int visitedUrls = 0;
        while (!queue.isEmpty() && pages.size() < maxPages) {
            Candidate candidate = queue.removeFirst();
            if (!visited.add(candidate.uri().toString())) continue;
            visitedUrls++;
            report(progressListener, maxPages, queued.size(), visitedUrls, pages.size(),
                    candidate.uri().toString());
            delay(lastRequestAt, options.requestDelay());
            FetchResult fetched = fetch(candidate.uri(), source, options, false);
            lastRequestAt = System.nanoTime();
            ParsedPage parsed = parse(fetched, source);
            if (!parsed.content().isBlank()) {
                var chunks = chunker.chunk(parsed.content(), options.chunkMaxTokens(), options.chunkOverlapTokens());
                if (!chunks.isEmpty()) {
                    pages.add(new KnowledgeStore.DocumentPage(candidate.uri().toString(), parsed.title(),
                            parsed.language(), version(candidate.uri()), KnowledgeDocumentChunker.sha256(parsed.content()),
                            parsed.content(), fetched.etag(), fetched.lastModified(), chunks));
                }
            }
            report(progressListener, maxPages, queued.size(), visitedUrls, pages.size(),
                    candidate.uri().toString());
            if (candidate.depth() >= Math.max(0, options.maxDepth())) continue;
            for (String link : parsed.links()) {
                enqueue(queue, queued, source, link, candidate.depth() + 1, robots);
            }
            report(progressListener, maxPages, queued.size(), visitedUrls, pages.size(),
                    candidate.uri().toString());
        }
        if (pages.isEmpty()) {
            throw new DocumentCollectionException(DocumentCollectionException.Code.UNSUPPORTED_CONTENT,
                    "Official documentation source returned no collectable text pages");
        }
        return List.copyOf(pages);
    }

    private static void report(ProgressListener listener, int maxPages, int discoveredUrls,
                               int visitedUrls, int collectedPages, String currentUrl) {
        listener.onProgress(new KnowledgeStore.CollectionProgress(
                maxPages, discoveredUrls, visitedUrls, collectedPages, currentUrl));
    }

    private ParsedPage parse(FetchResult fetched, KnowledgeStore.SourceTask source) {
        String contentType = fetched.contentType().toLowerCase(Locale.ROOT);
        if (contentType.contains("text/html") || contentType.contains("application/xhtml")) {
            Document document = Jsoup.parse(fetched.body(), fetched.uri().toString());
            List<String> links = document.select("a[href]").stream()
                    .map(element -> element.absUrl("href")).filter(value -> !value.isBlank()).toList();
            Element main = first(document, "main", "article", ".theme-doc-markdown", ".doc", "#content", "body");
            Element clean = main == null ? document.body() : main.clone();
            clean.select("script,style,noscript,svg,canvas,form,button,nav,footer,header,aside,.pagination-nav,.table-of-contents").remove();
            clean.select("p,blockquote").stream()
                    .filter(element -> isBoilerplate(element.text()))
                    .forEach(Element::remove);
            StringBuilder text = new StringBuilder();
            for (Element element : clean.select("h1,h2,h3,h4,h5,h6,p,li,pre,table")) {
                if (element.tagName().equals("p") && element.parent() != null
                        && element.parent().tagName().equals("li")) continue;
                String value = element.text().trim();
                if (value.isBlank()) continue;
                if (element.tagName().matches("h[1-6]")) {
                    int level = Integer.parseInt(element.tagName().substring(1));
                    text.append("#".repeat(level)).append(' ').append(value);
                } else if (element.tagName().equals("li")) {
                    text.append("- ").append(value);
                } else if (element.tagName().equals("pre")) {
                    text.append("```\n").append(element.wholeText().trim()).append("\n```");
                } else {
                    text.append(value);
                }
                text.append("\n\n");
            }
            String title = Optional.ofNullable(document.selectFirst("h1"))
                    .map(Element::text).filter(value -> !value.isBlank())
                    .orElseGet(() -> document.title().isBlank() ? source.name() : document.title());
            String language = Optional.ofNullable(document.selectFirst("html[lang]"))
                    .map(element -> element.attr("lang")).filter(value -> !value.isBlank()).orElse("en");
            return new ParsedPage(title, language, text.toString().trim(), links);
        }
        if (contentType.contains("text/plain") || contentType.contains("text/markdown")
                || fetched.uri().getPath().endsWith(".md")) {
            String content = cleanMarkdown(fetched.body());
            String title = content.lines().map(String::trim)
                    .filter(line -> line.startsWith("# ")).map(line -> line.substring(2).trim())
                    .findFirst().orElseGet(() -> fileName(fetched.uri()));
            return new ParsedPage(title, "en", content, markdownLinks(content));
        }
        throw new DocumentCollectionException(DocumentCollectionException.Code.UNSUPPORTED_CONTENT,
                "Unsupported official document content type: " + fetched.contentType());
    }

    private RobotsRules readRobots(KnowledgeStore.SourceTask source, CrawlOptions options) {
        try {
            URI robots = new URI("https", null, source.allowedHost(), -1, "/robots.txt", null, null);
            FetchResult fetched = fetch(robots, source, new CrawlOptions(
                    1, 0, Math.min(65_536, options.maxBytes()), options.requestTimeout(),
                    Duration.ZERO, options.chunkMaxTokens(), options.chunkOverlapTokens()), true);
            return RobotsRules.parse(fetched.body());
        } catch (DocumentCollectionException exception) {
            if (exception.code() == DocumentCollectionException.Code.HTTP_ERROR) return RobotsRules.allowAll();
            throw exception;
        } catch (URISyntaxException exception) {
            throw new DocumentCollectionException(DocumentCollectionException.Code.INTERNAL_ERROR,
                    "Unable to build robots.txt URL", exception);
        }
    }

    private FetchResult fetch(URI initial, KnowledgeStore.SourceTask source,
                              CrawlOptions options, boolean discoveryResource) {
        URI current = initial;
        for (int redirects = 0; redirects <= 3; redirects++) {
            validateResolved(current, source, discoveryResource);
            HttpRequest request = HttpRequest.newBuilder(current)
                    .GET().timeout(options.requestTimeout())
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,text/markdown,text/plain,application/xhtml+xml,application/xml;q=0.7")
                    .build();
            try {
                HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status >= 300 && status < 400) {
                    String location = response.headers().firstValue("location").orElseThrow(() ->
                            new DocumentCollectionException(DocumentCollectionException.Code.HTTP_ERROR,
                                    "Redirect response omitted Location"));
                    response.body().close();
                    current = validate(current.resolve(location).toString(), source, discoveryResource);
                    continue;
                }
                if (status == 429 || status >= 500) {
                    response.body().close();
                    throw new DocumentCollectionException(DocumentCollectionException.Code.TRANSIENT_REMOTE,
                            "Official documentation returned HTTP " + status);
                }
                if (status < 200 || status >= 300) {
                    response.body().close();
                    throw new DocumentCollectionException(DocumentCollectionException.Code.HTTP_ERROR,
                            "Official documentation returned HTTP " + status);
                }
                long declared = response.headers().firstValueAsLong("content-length").orElse(-1);
                if (declared > options.maxBytes()) {
                    throw new DocumentCollectionException(DocumentCollectionException.Code.CONTENT_TOO_LARGE,
                            "Official document exceeds configured byte limit");
                }
                byte[] bytes = readBounded(response.body(), options.maxBytes());
                String contentType = response.headers().firstValue("content-type").orElse("text/plain");
                return new FetchResult(current, new String(bytes, StandardCharsets.UTF_8), contentType,
                        response.headers().firstValue("etag").orElse(null),
                        response.headers().firstValue("last-modified").orElse(null));
            } catch (java.net.http.HttpTimeoutException exception) {
                throw new DocumentCollectionException(DocumentCollectionException.Code.TIMEOUT,
                        "Official documentation request timed out", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new DocumentCollectionException(DocumentCollectionException.Code.TRANSIENT_REMOTE,
                        "Official documentation request was interrupted", exception);
            } catch (IOException exception) {
                throw new DocumentCollectionException(DocumentCollectionException.Code.TRANSIENT_REMOTE,
                        "Official documentation request failed", exception);
            }
        }
        throw new DocumentCollectionException(DocumentCollectionException.Code.VALIDATION_ERROR,
                "Official documentation exceeded redirect limit");
    }

    private static byte[] readBounded(InputStream input, int maxBytes) throws IOException {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 65_536))) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > maxBytes) {
                    throw new DocumentCollectionException(DocumentCollectionException.Code.CONTENT_TOO_LARGE,
                            "Official document exceeds configured byte limit");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static URI validate(String raw, KnowledgeStore.SourceTask source, boolean discoveryResource) {
        try {
            URI uri = new URI(raw).normalize();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equals(scheme) || !host.equals(source.allowedHost().toLowerCase(Locale.ROOT))
                    || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
                throw validation("URL is outside the registered HTTPS host");
            }
            String path = uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath();
            if (List.of(path.split("/")).contains("..")) {
                throw validation("URL contains a parent path segment");
            }
            boolean exactDiscovery = discoveryResource && strip(uri).equals(strip(URI.create(source.discoveryUrl())));
            boolean robots = discoveryResource && "/robots.txt".equals(path);
            if (!exactDiscovery && !robots && !path.startsWith(source.allowedPathPrefix())) {
                throw validation("URL is outside the registered documentation path");
            }
            if (!discoveryResource
                    && SKIPPED_SUFFIXES.stream().anyMatch(path.toLowerCase(Locale.ROOT)::endsWith)) {
                throw validation("URL points to a non-document asset");
            }
            return strip(uri);
        } catch (URISyntaxException | IllegalArgumentException exception) {
            throw new DocumentCollectionException(DocumentCollectionException.Code.VALIDATION_ERROR,
                    "Invalid official documentation URL", exception);
        }
    }

    private static void validateResolved(URI uri, KnowledgeStore.SourceTask source, boolean discoveryResource) {
        validate(uri.toString(), source, discoveryResource);
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                    throw validation("Official documentation host resolved to a non-public address");
                }
            }
        } catch (UnknownHostException exception) {
            throw new DocumentCollectionException(DocumentCollectionException.Code.TRANSIENT_REMOTE,
                    "Unable to resolve official documentation host", exception);
        }
    }

    private static URI strip(URI uri) {
        try {
            return new URI(uri.getScheme().toLowerCase(Locale.ROOT), null,
                    uri.getHost().toLowerCase(Locale.ROOT), uri.getPort(),
                    uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath(), null, null);
        } catch (URISyntaxException exception) {
            throw validation("Unable to canonicalize official documentation URL");
        }
    }

    private static void enqueue(ArrayDeque<Candidate> queue, Set<String> queued,
                                KnowledgeStore.SourceTask source, String raw, int depth,
                                RobotsRules robots) {
        try {
            URI uri = validate(raw, source, false);
            if (!robots.allowed(uri.getPath()) || !queued.add(uri.toString())) return;
            queue.addLast(new Candidate(uri, depth));
        } catch (DocumentCollectionException ignored) {
            // Links outside the registered source boundary are intentionally ignored.
        }
    }

    private static List<String> markdownLinks(String body) {
        LinkedHashSet<String> links = new LinkedHashSet<>();
        Matcher matcher = MARKDOWN_LINK.matcher(body == null ? "" : body);
        while (matcher.find()) links.add(matcher.group(1).trim());
        return List.copyOf(links);
    }

    static String cleanMarkdown(String body) {
        return java.util.Arrays.stream((body == null ? "" : body)
                        .replace("\r\n", "\n").replace('\r', '\n')
                        .split("\n\\s*\n"))
                .map(String::trim)
                .filter(block -> !isBoilerplate(block))
                .collect(Collectors.joining("\n\n"))
                .trim();
    }

    static boolean isBoilerplate(String text) {
        String normalized = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        return (normalized.contains("documentation index") && normalized.contains("llms.txt"))
                || normalized.startsWith("for the latest stable version, please use spring ai");
    }

    private static String version(URI uri) {
        Matcher matcher = VERSION_PATH.matcher(uri.getPath());
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String fileName(URI uri) {
        String path = uri.getPath();
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.isBlank() ? uri.getHost() : name.replace(".md", "").replace('-', ' ');
    }

    private static Element first(Document document, String... selectors) {
        for (String selector : selectors) {
            Element element = document.selectFirst(selector);
            if (element != null) return element;
        }
        return null;
    }

    private static void delay(long previousRequestAt, Duration requestDelay) {
        if (previousRequestAt == 0 || requestDelay.isZero() || requestDelay.isNegative()) return;
        long remaining = requestDelay.toNanos() - (System.nanoTime() - previousRequestAt);
        if (remaining <= 0) return;
        try {
            Thread.sleep(Math.max(1, Duration.ofNanos(remaining).toMillis()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DocumentCollectionException(DocumentCollectionException.Code.TRANSIENT_REMOTE,
                    "Official documentation crawl was interrupted", exception);
        }
    }

    private static DocumentCollectionException validation(String message) {
        return new DocumentCollectionException(DocumentCollectionException.Code.VALIDATION_ERROR, message);
    }

    static final class RobotsRules {
        private final List<String> allow;
        private final List<String> disallow;

        private RobotsRules(List<String> allow, List<String> disallow) {
            this.allow = allow; this.disallow = disallow;
        }

        static RobotsRules allowAll() { return new RobotsRules(List.of(), List.of()); }

        static RobotsRules parse(String body) {
            List<String> allow = new ArrayList<>();
            List<String> disallow = new ArrayList<>();
            boolean applies = false;
            for (String raw : (body == null ? "" : body).split("\\R")) {
                String line = raw.replaceFirst("#.*$", "").trim();
                if (line.isBlank() || !line.contains(":")) continue;
                String key = line.substring(0, line.indexOf(':')).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(line.indexOf(':') + 1).trim();
                if (key.equals("user-agent")) {
                    applies = value.equals("*") || value.toLowerCase(Locale.ROOT).contains("insightops");
                } else if (applies && key.equals("allow") && !value.isBlank()) {
                    allow.add(value);
                } else if (applies && key.equals("disallow") && !value.isBlank()) {
                    disallow.add(value);
                }
            }
            return new RobotsRules(List.copyOf(allow), List.copyOf(disallow));
        }

        boolean allowed(String path) {
            String bestAllow = allow.stream().filter(path::startsWith)
                    .max(Comparator.comparingInt(String::length)).orElse("");
            String bestDisallow = disallow.stream().filter(path::startsWith)
                    .max(Comparator.comparingInt(String::length)).orElse("");
            return bestDisallow.isEmpty() || bestAllow.length() >= bestDisallow.length();
        }
    }

    private record Candidate(URI uri, int depth) { }
    private record FetchResult(URI uri, String body, String contentType, String etag, String lastModified) { }
    private record ParsedPage(String title, String language, String content, List<String> links) { }
}
