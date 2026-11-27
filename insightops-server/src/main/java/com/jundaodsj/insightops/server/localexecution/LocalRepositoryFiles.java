package com.jundaodsj.insightops.server.localexecution;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class LocalRepositoryFiles {
    private static final int MAX_EDIT_BYTES = 64 * 1024;
    private static final int MAX_READ_BYTES = 256 * 1024;
    private static final int MAX_EXCERPT = 10_000;
    private static final int MAX_MATCHES = 24;
    private static final Set<String> TEXT_SUFFIXES = Set.of("java", "xml", "properties", "yml", "yaml", "json",
            "md", "txt", "sql", "html", "css", "js", "ts", "adoc", "csv");
    private static final Set<String> PRIVATE_SEGMENTS = Set.of("credentials", "secrets", "id_rsa", "id_ed25519");

    record Listing(List<String> paths, boolean truncated, Integer nextPage) {}
    record Match(String path, int line, String text) {}
    record Search(List<Match> matches, boolean truncated, Integer nextPage) {}
    record Excerpt(String path, String sha256, int fromLine, int toLine, int totalLines, String text,
                   boolean truncated) {}
    record WriteReceipt(String path, boolean created, String sha256, int size) {}
    record Edit(String path, String expectedSha256, String content) {}
    private record Change(Path file, byte[] before, byte[] after) {}

    private final Path root;
    private final List<String> scopes;
    private final Set<Path> createdTestFiles = new HashSet<>();

    LocalRepositoryFiles(Path root, List<String> scopes) {
        this.root = root;
        this.scopes = scopes;
    }

    Path root() { return root; }

    static List<String> writeScopes(List<String> raw) {
        if (raw == null || raw.isEmpty() || raw.size() > 16) {
            throw new IllegalArgumentException("请选择 1 至 16 个允许写入的源文件或目录。");
        }
        List<String> paths = new ArrayList<>();
        for (String value : raw) {
            if (value == null) throw new IllegalArgumentException("写入范围缺少路径。");
            boolean directory = value.endsWith("/");
            String name = relative(directory ? value.substring(0, value.length() - 1) : value, false);
            String path = directory ? name + "/" : name;
            if (!path.startsWith("src/main/") && !path.startsWith("src/test/") &&
                    (directory || (!path.equals("pom.xml") && !path.equals("README.md")))) {
                throw new IllegalArgumentException("写入范围须位于 src/main、src/test、pom.xml 或 README.md。");
            }
            if (!directory) requireTextFile(path);
            if (!paths.contains(path)) paths.add(path);
        }
        return List.copyOf(paths);
    }

    Listing list(String directory, String pathContains, int page) throws IOException {
        String filter = pathContains.toLowerCase(Locale.ROOT);
        List<String> paths = readableFiles(directory).stream().map(this::name)
                .filter(path -> path.toLowerCase(Locale.ROOT).contains(filter)).sorted().toList();
        int from = Math.min(paths.size(), (page - 1) * 160);
        int end = Math.min(paths.size(), from + 160);
        return new Listing(paths.subList(from, end), end < paths.size(), end < paths.size() ? page + 1 : null);
    }

    Search search(String directory, String query, int page) throws IOException {
        if (query == null || query.isBlank() || query.length() > 160 || query.contains("\n") || query.contains("\r")) {
            throw new IllegalArgumentException("搜索内容须为 1 至 160 字符的单行文字。");
        }
        String needle = query.toLowerCase(Locale.ROOT);
        List<Match> matches = new ArrayList<>();
        int skip = (page - 1) * MAX_MATCHES;
        for (Path file : readableFiles(directory)) {
            if (Files.size(file) > MAX_READ_BYTES) continue;
            List<String> lines;
            try {
                lines = utf8(Files.readAllBytes(file)).lines().toList();
            } catch (CharacterCodingException ignored) {
                continue;
            }
            for (int i = 0; i < lines.size(); i++) {
                var line = lines.get(i);
                if (!line.toLowerCase(Locale.ROOT).contains(needle)) continue;
                if (skip > 0) { skip--; continue; }
                if (matches.size() == MAX_MATCHES) return new Search(List.copyOf(matches), true, page + 1);
                matches.add(new Match(name(file), i + 1, small(line, 340)));
            }
        }
        return new Search(List.copyOf(matches), false, null);
    }

    Excerpt read(String value, int first, int last) throws IOException {
        String name = relative(value, false);
        requireTextFile(name);
        var file = resolved(name);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_READ_BYTES) {
            throw new IllegalArgumentException("该路径没有受支持的 UTF-8 文本文件，或文件过大。");
        }
        if (first < 1 || last < first || last - first >= 300) {
            throw new IllegalArgumentException("一次可读取 1 至 300 行。");
        }
        var raw = Files.readAllBytes(file);
        List<String> lines = utf8(raw).lines().toList();
        if (lines.isEmpty() && first == 1) return new Excerpt(name, LocalWorkspaces.sha(raw), 1, 0, 0, "", false);
        if (first > lines.size()) throw new IllegalArgumentException("起始行超过文件行数。");
        int end = Math.min(last, lines.size());
        String excerpt = String.join("\n", lines.subList(first - 1, end));
        return new Excerpt(name, LocalWorkspaces.sha(raw), first, end, lines.size(), small(excerpt, MAX_EXCERPT),
                excerpt.length() > MAX_EXCERPT);
    }

    WriteReceipt write(String value, String expectedHash, String content) throws IOException {
        return writeAll(List.of(new Edit(value, expectedHash, content))).getFirst();
    }

    List<WriteReceipt> writeAll(List<Edit> edits) throws IOException {
        if (edits.isEmpty() || edits.size() > 8) throw new IllegalArgumentException("一次变更需要 1 至 8 个文件。");
        List<Change> changes = new ArrayList<>();
        Set<Path> unique = new HashSet<>();
        for (var edit : edits) {
            Change change = validatedEdit(edit);
            if (!unique.add(change.file())) throw new IllegalArgumentException("同一组变更包含重复文件。");
            changes.add(change);
        }
        int written = 0;
        try {
            for (var change : changes) {
                replace(change.file(), change.after());
                written++;
            }
        } catch (IOException failure) {
            for (int i = written - 1; i >= 0; i--) {
                var previous = changes.get(i);
                try {
                    if (previous.before() == null) Files.deleteIfExists(previous.file());
                    else replace(previous.file(), previous.before());
                } catch (IOException recovery) { failure.addSuppressed(recovery); }
            }
            throw failure;
        }
        List<WriteReceipt> receipts = new ArrayList<>();
        for (var change : changes) {
            String name = name(change.file());
            boolean created = change.before() == null;
            if (created && name.startsWith("src/test/")) createdTestFiles.add(change.file());
            receipts.add(new WriteReceipt(name, created, LocalWorkspaces.sha(change.after()), change.after().length));
        }
        return List.copyOf(receipts);
    }

    private Change validatedEdit(Edit edit) throws IOException {
        String name = relative(edit.path(), false);
        requireTextFile(name);
        if (!writable(name)) throw new IllegalArgumentException("该文件不在本次任务授权的写入范围。");
        var file = resolved(name);
        String expectedHash = edit.expectedSha256();
        byte[] before = null;
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (!expectedHash.isEmpty()) throw new IllegalArgumentException("新文件的旧 SHA-256 应为空字符串。");
        } else {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_EDIT_BYTES ||
                    !expectedHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("文件与期望 SHA-256 不一致，请重新读取当前版本。");
            }
            before = Files.readAllBytes(file);
            if (!expectedHash.equals(LocalWorkspaces.sha(before))) {
                throw new IllegalArgumentException("文件与期望 SHA-256 不一致，请重新读取当前版本。");
            }
            if (name.startsWith("src/test/") && !createdTestFiles.contains(file)) {
                throw new IllegalArgumentException("保留已有测试，回归用例须写入新测试文件。");
            }
        }
        byte[] bytes = edit.content().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_EDIT_BYTES || edit.content().indexOf('\0') >= 0) {
            throw new IllegalArgumentException("单次写入须为最多 64 KiB 的 UTF-8 文本。");
        }
        if (name.equals("pom.xml")) LocalWorkspaces.verifyPom(bytes);
        return new Change(file, before, bytes);
    }

    private static void replace(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        var temporary = Files.createTempFile(file.getParent(), ".insightops-write-", ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    void snapshot(Path output) throws IOException {
        long bytes = 0;
        for (Path source : tree(root, false)) {
            if ((bytes += Files.size(source)) > LocalWorkspaces.MAX_SOURCE_BYTES) {
                throw new IllegalArgumentException("修改后的源码超出本地构建大小上限。");
            }
            var destination = output.resolve(root.relativize(source));
            Files.createDirectories(destination.getParent());
            Files.copy(source, destination);
        }
    }

    private boolean writable(String name) {
        return scopes.stream().anyMatch(scope -> name.equals(scope) || scope.endsWith("/") && name.startsWith(scope));
    }

    private List<Path> readableFiles(String directory) throws IOException {
        String name = relative(directory, true);
        var start = resolved(name);
        if (!Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("目录不存在。");
        return tree(start, true).stream().filter(p -> textFile(name(p))).sorted(Comparator.naturalOrder()).toList();
    }

    private List<Path> tree(Path start, boolean forTool) throws IOException {
        List<Path> paths = new ArrayList<>();
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                String leaf = dir.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!dir.equals(start) && (leaf.equals(".git") || leaf.equals("target") ||
                        forTool && (leaf.startsWith(".") || PRIVATE_SEGMENTS.contains(leaf)))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!dir.toRealPath().startsWith(root)) throw new IOException("源码目录解析到了工作副本之外。");
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile() || !file.toRealPath().startsWith(root)) {
                    throw new IOException("源码副本含不支持的链接或特殊文件。");
                }
                if (paths.size() == LocalWorkspaces.MAX_FILES) throw new IOException("源码文件数量超过上限。");
                paths.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        return paths;
    }

    private Path resolved(String name) throws IOException {
        Path current = root;
        if (name.isEmpty()) return current;
        for (String part : name.split("/")) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) &&
                    (Files.isSymbolicLink(current) || !current.toRealPath().equals(current))) {
                throw new IllegalArgumentException("工具路径不能经过链接或其他位置。");
            }
        }
        return current;
    }

    private String name(Path file) { return root.relativize(file).toString().replace('\\', '/'); }

    private static String relative(String value, boolean directory) {
        if (directory && ("".equals(value) || ".".equals(value))) return "";
        if (value == null || value.length() > 300 || value.isBlank() || value.indexOf('\\') >= 0 ||
                value.indexOf(':') >= 0 || value.endsWith("/")) throw new IllegalArgumentException("请使用仓库内相对路径。");
        for (String part : value.split("/", -1)) {
            String lower = part.toLowerCase(Locale.ROOT);
            if (!part.matches("[\\p{L}\\p{N}_][\\p{L}\\p{N} _.()@+-]{0,119}") || part.endsWith(".") ||
                    part.endsWith(" ") || lower.equals("target") || PRIVATE_SEGMENTS.contains(lower) ||
                    lower.matches("(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?")) {
                throw new IllegalArgumentException("路径包含不允许的目录或文件名。");
            }
        }
        return value;
    }

    private static void requireTextFile(String name) {
        if (!textFile(name)) throw new IllegalArgumentException("请选择 Java、Maven 配置或受支持的文本资源。");
    }

    private static boolean textFile(String name) {
        String leaf = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        int dot = leaf.lastIndexOf('.');
        return !leaf.startsWith(".") && !leaf.startsWith("credentials.") && !leaf.startsWith("secrets.") &&
                (leaf.equals("license") || dot > 0 && TEXT_SUFFIXES.contains(leaf.substring(dot + 1)));
    }

    private static String utf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static String small(String value, int max) { return value.substring(0, Math.min(max, value.length())); }
}
