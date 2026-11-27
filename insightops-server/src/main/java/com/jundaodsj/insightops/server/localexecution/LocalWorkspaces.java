package com.jundaodsj.insightops.server.localexecution;

import com.jundaodsj.insightops.businesssystem.application.MavenPomFacts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

final class LocalWorkspaces {
    static final int MAX_PATCH = 1024 * 1024;
    static final int MAX_FILES = 6000;
    static final long MAX_SOURCE_BYTES = 96L * 1024 * 1024;
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(45);
    private static final String WORKSPACE_ID = "[A-Za-z0-9][A-Za-z0-9_.-]{0,79}";
    private static final String REVISION = "[0-9a-f]{40}";

    record Workspace(String id, String revision) {}

    private final Path catalog;
    private final UUID legacyWorkspaceId;
    private final Path data;
    private final Path mavenRepository;
    private final Map<String, String> gitEnvironment;

    LocalWorkspaces(LocalExecutionProperties properties) throws IOException {
        catalog = configuredDirectory(properties.workspacesRoot(), false, "仓库登记目录");
        legacyWorkspaceId = properties.legacyWorkspaceId();
        data = configuredDirectory(properties.dataRoot(), true, "运行数据目录");
        mavenRepository = configuredDirectory(properties.mavenRepository(), false, "Maven 缓存目录");
        for (List<Path> pair : List.of(List.of(data, catalog), List.of(mavenRepository, data),
                List.of(mavenRepository, catalog))) {
            if (pair.getFirst().startsWith(pair.getLast()) || pair.getLast().startsWith(pair.getFirst())) {
                throw new IllegalArgumentException("仓库、运行数据和 Maven 缓存须使用独立目录。");
            }
        }
        Files.createDirectories(data.resolve("empty-hooks"));
        var emptyConfig = data.resolve("empty-git-config");
        Files.writeString(emptyConfig, "", StandardCharsets.UTF_8);
        gitEnvironment = Map.of("GIT_CONFIG_NOSYSTEM", "1", "GIT_CONFIG_GLOBAL", emptyConfig.toString(),
                "GIT_TERMINAL_PROMPT", "0", "GIT_OPTIONAL_LOCKS", "0", "GIT_LFS_SKIP_SMUDGE", "1");
    }

    Path data() { return data; }
    Path mavenRepository() { return mavenRepository; }

    List<Workspace> list(UUID tenant) throws IOException {
        List<Workspace> workspaces = new ArrayList<>();
        Path root = catalogFor(tenant);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (var dirs = Files.list(root)) {
            for (Path child : dirs.filter(p -> p.getFileName().toString().matches(WORKSPACE_ID))
                    .filter(LocalWorkspaces::gitDirectory).sorted(Comparator.naturalOrder()).limit(16).toList()) {
                try {
                    workspaces.add(check(tenant, child.getFileName().toString(), LocalProcess.ACTIVE));
                } catch (IOException | IllegalArgumentException ignored) {
                    // A broken or temporarily inaccessible checkout is not selectable.
                }
            }
        }
        return List.copyOf(workspaces);
    }

    Workspace check(UUID tenant, String id, BooleanSupplier active) throws IOException {
        var source = registered(tenant, id);
        var revision = git(source, active, 500, "rev-parse", "--verify", "HEAD^{commit}").text().strip();
        if (!revision.matches(REVISION)) throw new IllegalArgumentException("当前登记仓库没有可用的 Git 提交。");
        if (!git(source, active, 500, "cat-file", "-t", revision + ":pom.xml").text().strip().equals("blob")) {
            throw new IllegalArgumentException("登记仓库没有根 pom.xml 文件。");
        }
        return new Workspace(id, revision);
    }

    Path prepare(UUID tenant, Workspace frozen, String expectedPom, Path runDirectory, BooleanSupplier active) throws IOException {
        var source = registered(tenant, frozen.id());
        var root = checkout(source, frozen.revision(), runDirectory.resolve("workspace"), active);
        Path pom = root.resolve("pom.xml");
        if (!Files.isRegularFile(pom, LinkOption.NOFOLLOW_LINKS) || Files.size(pom) > MAX_PATCH) {
            throw new IllegalArgumentException("指定提交缺少受支持的根 pom.xml。");
        }
        byte[] bytes = Files.readAllBytes(pom);
        if (!sha(bytes).equals(expectedPom)) {
            throw new IllegalArgumentException("登记仓库该提交的 pom.xml 与冻结的工程基线字节不一致。");
        }
        verifyPom(bytes);
        return root;
    }

    private Path checkout(Path source, String revision, Path destination, BooleanSupplier active) throws IOException {
        var root = Files.createDirectory(destination);
        git(root, active, 16_000, "init", "--quiet");
        git(root, active, 32_000, "-c", "protocol.file.allow=always", "fetch", "--quiet", "--no-tags", "--depth=1",
                "--", source.toUri().toString(), revision);
        checkTree(root, revision, active);
        git(root, active, 32_000, "checkout", "--quiet", "--detach", revision);
        if (!git(root, active, 32_000, "status", "--porcelain", "--untracked-files=all").text().isBlank()) {
            throw new IllegalArgumentException("本机工作副本无法还原为干净的指定提交。");
        }

        return root;
    }

    // diff() already staged the final candidate. Replay only newly added test sources and fixtures.
    List<String> newTestFiles(Path candidate, BooleanSupplier active) throws IOException {
        var names = git(candidate, active, MAX_PATCH, "diff", "--cached", "--diff-filter=A", "--name-only", "-z",
                "HEAD", "--", "src/test/").text();
        return names.isEmpty() ? List.of() : List.of(names.split("\0"));
    }

    LocalRepositoryFiles withNewTests(Path candidate, String revision, Path destination,
                                     List<String> names, BooleanSupplier active) throws IOException {
        var root = checkout(candidate, revision, destination, active);
        for (String name : names) {
            var from = candidate.resolve(name).normalize();
            var to = root.resolve(name).normalize();
            if (!name.startsWith("src/test/") || !from.startsWith(candidate) || !to.startsWith(root)
                    || !Files.isRegularFile(from, LinkOption.NOFOLLOW_LINKS) || !from.toRealPath().equals(from)) {
                throw new IOException("新增测试原件路径不在候选源码内。");
            }
            Files.createDirectories(to.getParent());
            Files.copy(from, to);
        }
        return new LocalRepositoryFiles(root, List.of());
    }

    static void verifyPom(byte[] bytes) {
        MavenPomFacts.read(bytes, List.of(), new ArrayList<>(), new ArrayList<>());
    }

    byte[] diff(Path root, BooleanSupplier active) throws IOException {
        git(root, active, 14_000, "add", "--all", "--force", "--", ".");
        return git(root, active, MAX_PATCH, "diff", "--cached", "--no-ext-diff", "--no-textconv", "--no-color",
                "--binary", "--full-index", "HEAD", "--", ".").bytes();
    }

    private void checkTree(Path root, String revision, BooleanSupplier active) throws IOException {
        var tree = git(root, active, 2 * MAX_PATCH, "ls-tree", "-rlz", revision).text();
        long bytes = 0;
        int files = 0;
        for (String entry : tree.split("\0")) {
            if (entry.isEmpty()) continue;
            var header = entry.substring(0, entry.indexOf('\t')).strip().split(" +");
            if (!header[0].equals("100644") && !header[0].equals("100755")) {
                throw new IllegalArgumentException("首版不能在含符号链接或子模块的提交上执行。");
            }
            bytes += Long.parseLong(header[3]);
            if (++files > MAX_FILES || bytes > MAX_SOURCE_BYTES) {
                throw new IllegalArgumentException("指定提交超出本地执行的源码大小上限。");
            }
        }
    }

    private LocalProcess.Result git(Path root, BooleanSupplier active, int max, String... arguments) throws IOException {
        var command = new ArrayList<>(List.of("git", "--literal-pathspecs", "-c", "core.autocrlf=false", "-c",
                "core.longpaths=true", "-c", "core.hooksPath=" + data.resolve("empty-hooks"), "-c",
                "init.templateDir=" + data.resolve("empty-hooks"), "-c", "protocol.allow=never", "-c",
                "core.protectNTFS=true", "-c", "core.protectHFS=true"));
        command.addAll(List.of(arguments));
        var result = LocalProcess.run(command, root, GIT_TIMEOUT, max, gitEnvironment, active, null);
        if (!result.ok()) {
            if (result.truncated()) throw new IOException("Git 树或差异超出本地执行证据上限。");
            var message = result.text().strip();
            throw new IOException("Git 操作未完成（" + result.end() + "）" +
                    (message.isEmpty() ? "。" : "。" + message.substring(0, Math.min(message.length(), 500))));
        }
        return result;
    }

    private Path catalogFor(UUID tenant) throws IOException {
        if (tenant == null) throw new IllegalArgumentException("本地仓库需要认证工作区。");
        Path root = tenant.equals(legacyWorkspaceId) ? catalog : catalog.resolve(tenant.toString());
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS) && !root.toRealPath().equals(root)) {
            throw new IOException("工作区登记目录不能通过链接指向其他目录。");
        }
        return root;
    }

    private Path registered(UUID tenant, String id) throws IOException {
        Path allowed = catalogFor(tenant);
        if (id == null || !id.matches(WORKSPACE_ID) || !gitDirectory(allowed.resolve(id))) {
            throw new IllegalArgumentException("请选择本机已登记的独立 Git 仓库。");
        }
        var root = allowed.resolve(id);
        if (!root.toRealPath().equals(root) || !root.resolve(".git").toRealPath().equals(root.resolve(".git"))) {
            throw new IllegalArgumentException("登记仓库或其 Git 元数据不能指向其他目录。");
        }
        return root;
    }

    private static boolean gitDirectory(Path root) {
        return Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) &&
                Files.isDirectory(root.resolve(".git"), LinkOption.NOFOLLOW_LINKS);
    }

    private static Path configuredDirectory(Path directory, boolean create, String label) throws IOException {
        if (directory == null || !directory.isAbsolute() || directory.toString().contains(",")) {
            throw new IllegalArgumentException(label + "须配置为不含逗号的本机绝对路径。");
        }
        if (create) Files.createDirectories(directory);
        var real = directory.toRealPath();
        if (!Files.isDirectory(real)) throw new IllegalArgumentException(label + "不存在。");
        return real;
    }

    static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
