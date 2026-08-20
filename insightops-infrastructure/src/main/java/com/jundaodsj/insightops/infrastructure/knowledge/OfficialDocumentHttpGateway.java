package com.jundaodsj.insightops.infrastructure.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.knowledge.application.DocumentCollectionException;
import com.jundaodsj.insightops.knowledge.application.KnowledgeStore;
import com.jundaodsj.insightops.knowledge.application.OfficialDocumentGateway;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
    private final ObjectMapper json;
    private final SyndicationFeedParser feedParser;
    private final UploadedDocumentCollector uploadCollector;
    private final String githubToken;

    @Autowired
    public OfficialDocumentHttpGateway(KnowledgeDocumentChunker chunker, ObjectMapper json,
                                       UploadedDocumentCollector uploadCollector,
                                       @Value("${insightops.tool.github.token:}") String githubToken) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build(), chunker, json, uploadCollector, githubToken);
    }

    public OfficialDocumentHttpGateway(KnowledgeDocumentChunker chunker) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build(), chunker, new ObjectMapper(), null, "");
    }

    OfficialDocumentHttpGateway(HttpClient http, KnowledgeDocumentChunker chunker) {
        this(http, chunker, new ObjectMapper(), null, "");
    }

    OfficialDocumentHttpGateway(HttpClient http, KnowledgeDocumentChunker chunker, ObjectMapper json,
                                UploadedDocumentCollector uploadCollector, String githubToken) {
        this.http = http;
        this.chunker = chunker;
        this.json = json;
        this.feedParser = new SyndicationFeedParser();
        this.uploadCollector = uploadCollector;
        this.githubToken = githubToken == null ? "" : githubToken.trim();
    }

    @Override
    public List<KnowledgeStore.DocumentPage> collect(KnowledgeStore.SourceTask source, CrawlOptions options,
                                                     ProgressListener progressListener) {
        if ("USER_UPLOAD".equals(source.sourceType())) {
            if (uploadCollector == null) {
                throw new DocumentCollectionException(DocumentCollectionException.Code.INTERNAL_ERROR,
                        "Upload collector is unavailable");
            }
            return uploadCollector.collect(source, options, progressListener);
        }
        if ("OFFICIAL_BLOG_RSS".equals(source.sourceType())) {
            return collectFeed(source, options, progressListener);
        }
        if ("OFFICIAL_ROADMAP".equals(source.sourceType())
                && source.allowedHost().equalsIgnoreCase("api.github.com")) {
            return collectGitHubMilestones(source, options, progressListener);
        }
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

    private List<KnowledgeStore.DocumentPage> collectFeed(
            KnowledgeStore.SourceTask source, CrawlOptions options, ProgressListener listener) {
        URI discovery = validate(source.discoveryUrl(), source, true);
        int maxPages = Math.max(1, options.maxPages());
        report(listener, maxPages, 1, 0, 0, discovery.toString());
        FetchResult fetched = fetch(discovery, source, options, true, true);
        if (fetched.notModified()) {
            report(listener, maxPages, 1, 1, 0, discovery.toString());
            return List.of();
        }
        List<SyndicationFeedParser.FeedItem> items;
        try {
            items = feedParser.parse(fetched.body(), discovery.toString(), maxPages);
        } catch (IllegalArgumentException exception) {
            throw new DocumentCollectionException(DocumentCollectionException.Code.UNSUPPORTED_CONTENT,
                    "Official RSS/Atom feed could not be parsed", exception);
        }
        List<KnowledgeStore.DocumentPage> pages = new ArrayList<>();
        int visited = 0;
        for (var item : items) {
            visited++;
            URI canonical;
            try {
                canonical = validate(item.link(), source, false);
            } catch (DocumentCollectionException exception) {
                continue;
            }
            report(listener, maxPages, items.size(), visited, pages.size(), canonical.toString());
            var chunks = chunker.chunk(item.content(), options.chunkMaxTokens(), options.chunkOverlapTokens());
            if (!chunks.isEmpty()) {
                pages.add(new KnowledgeStore.DocumentPage(canonical.toString(), item.title(), "en",
                        truncate(item.published(), 128), KnowledgeDocumentChunker.sha256(item.content()),
                        item.content(), fetched.etag(), fetched.lastModified(), chunks));
            }
            report(listener, maxPages, items.size(), visited, pages.size(), canonical.toString());
        }
        if (pages.isEmpty()) {
            throw new DocumentCollectionException(DocumentCollectionException.Code.UNSUPPORTED_CONTENT,
                    "RSS/Atom feed returned no entries inside the registered source boundary");
        }
        return List.copyOf(pages);
    }

    private List<KnowledgeStore.DocumentPage> collectGitHubMilestones(
            KnowledgeStore.SourceTask source, CrawlOptions options, ProgressListener listener) {
        URI discovery = validate(source.discoveryUrl(), source, true);
        int maxPages = Math.max(1, options.maxPages());
        report(listener, maxPages, 1, 0, 0, discovery.toString());
        FetchResult fetched = fetch(discovery, source, options, true, true);
        if (fetched.notModified()) {
            report(listener, maxPages, 1, 1, 0, discovery.toString());
            return List.of();
        }
        try {
            JsonNode root = json.readTree(fetched.body());
            if (!root.isArray()) throw new IllegalArgumentException("Milestone response is not an array");
            List<KnowledgeStore.DocumentPage> pages = new ArrayList<>();
            int discovered = Math.min(root.size(), maxPages);
            for (JsonNode milestone : root) {
                if (pages.size() >= maxPages) break;
                int number = milestone.path("number").asInt(0);
                String title = milestone.path("title").asText("Milestone " + number).strip();
                String url = safeGitHubUrl(milestone.path("html_url").asText(), source, number);
                String content = "# " + title + "\n\nState: " + milestone.path("state").asText("open")
                        + "\n\nDue: " + milestone.path("due_on").asText("not set")
                        + "\n\nOpen issues: " + milestone.path("open_issues").asInt(0)
                        + "\n\nClosed issues: " + milestone.path("closed_issues").asInt(0)
                        + "\n\n" + milestone.path("description").asText("").strip();
                var chunks = chunker.chunk(content, options.chunkMaxTokens(), options.chunkOverlapTokens());
                if (!chunks.isEmpty()) pages.add(new KnowledgeStore.DocumentPage(url, title, "en",
                        truncate(milestone.path("updated_at").asText(), 128),
                        KnowledgeDocumentChunker.sha256(content), content,
                        fetched.etag(), fetched.lastModified(), chunks));
                report(listener, maxPages, discovered, pages.size(), pages.size(), url);
            }
            return List.copyOf(pages);
        } catch (IOException | IllegalArgumentException exception) {
            throw new DocumentCollectionException(DocumentCollectionException.Code.UNSUPPORTED_CONTENT,
                    "GitHub milestone roadmap could not be parsed", exception);
        }
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
        return fetch(initial, source, options, discoveryResource, false);
    }

    private FetchResult fetch(URI initial, KnowledgeStore.SourceTask source,
                              CrawlOptions options, boolean discoveryResource, boolean conditional) {
        URI current = initial;
        for (int redirects = 0; redirects <= 3; redirects++) {
            validateResolved(current, source, discoveryResource);
            HttpRequest.Builder builder = HttpRequest.newBuilder(current)
                    .GET().timeout(options.requestTimeout())
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,text/markdown,text/plain,application/xhtml+xml,application/xml,application/rss+xml,application/atom+xml,application/json;q=0.8");
            if (conditional && source.fetchEtag() != null && !source.fetchEtag().isBlank()) {
                builder.header("If-None-Match", source.fetchEtag());
            }
            if (conditional && source.fetchLastModified() != null && !source.fetchLastModified().isBlank()) {
                builder.header("If-Modified-Since", source.fetchLastModified());
            }
            if ("api.github.com".equalsIgnoreCase(current.getHost()) && !githubToken.isBlank()) {
                builder.header("Authorization", "Bearer " + githubToken);
            }
            HttpRequest request = builder.build();
            try {
                HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status == 304 && conditional) {
                    response.body().close();
                    return new FetchResult(current, "", response.headers().firstValue("content-type").orElse(""),
                            source.fetchEtag(), source.fetchLastModified(), true);
                }
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
                        response.headers().firstValue("last-modified").orElse(null), false);
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

    private static String safeGitHubUrl(String raw, KnowledgeStore.SourceTask source, int number) {
        try {
            URI value = URI.create(raw).normalize();
            if ("https".equalsIgnoreCase(value.getScheme())
                    && "github.com".equalsIgnoreCase(value.getHost())
                    && value.getUserInfo() == null && value.getPort() == -1
                    && githubMilestonePathMatchesSource(value.getPath(), source)) {
                return strip(value).toString();
            }
        } catch (IllegalArgumentException ignored) {
            // Use the registered API resource as the canonical fallback.
        }
        String base = source.rootUrl().endsWith("/")
                ? source.rootUrl().substring(0, source.rootUrl().length() - 1) : source.rootUrl();
        return validate(base + "/" + Math.max(0, number), source, false).toString();
    }

    private static boolean githubMilestonePathMatchesSource(String htmlPath, KnowledgeStore.SourceTask source) {
        String apiPath = URI.create(source.discoveryUrl()).getPath();
        String prefix = "/repos/";
        String suffix = "/milestones";
        if (apiPath == null || !apiPath.startsWith(prefix) || !apiPath.endsWith(suffix)) return false;
        String repository = apiPath.substring(prefix.length(), apiPath.length() - suffix.length());
        String[] parts = repository.split("/");
        if (parts.length != 2 || htmlPath == null) return false;
        String expected = "/" + parts[0] + "/" + parts[1] + "/milestone/";
        if (!htmlPath.startsWith(expected)) return false;
        String number = htmlPath.substring(expected.length());
        if (number.isBlank() || number.contains("/")) return false;
        try {
            return Integer.parseInt(number) > 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static String truncate(String value, int max) {
        if (value == null || value.isBlank()) return null;
        return value.length() <= max ? value : value.substring(0, max);
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
    private record FetchResult(URI uri, String body, String contentType, String etag,
                               String lastModified, boolean notModified) { }
    private record ParsedPage(String title, String language, String content, List<String> links) { }
}
