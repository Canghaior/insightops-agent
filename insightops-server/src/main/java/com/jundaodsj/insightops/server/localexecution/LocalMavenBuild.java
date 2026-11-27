package com.jundaodsj.insightops.server.localexecution;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

final class LocalMavenBuild {
    private static final int MAX_ARTIFACT = 2 * 1024 * 1024;
    private static final int MAX_TOTAL = 12 * 1024 * 1024;
    private static final Duration DOCKER_CONTROL_TIMEOUT = Duration.ofSeconds(10);
    private static final List<String> DOCKER = List.of("docker", "--host", LocalProcess.WINDOWS
            ? "npipe:////./pipe/dockerDesktopLinuxEngine" : "unix:///var/run/docker.sock");

    record Build(String label, String patchSha256, String imageId, Path artifacts, LocalProcess.Result execution,
                 List<Path> xml, String collectionNote, List<String> command) {}

    private final LocalExecutionProperties config;
    private final LocalWorkspaces workspaces;
    private final ObjectMapper json;

    LocalMavenBuild(LocalExecutionProperties config, LocalWorkspaces workspaces, ObjectMapper json) {
        this.config = config;
        this.workspaces = workspaces;
        this.json = json;
    }

    Build run(UUID id, String label, String patchSha, LocalRepositoryFiles files, Path runDirectory,
              LocalBuildPlan plan, BooleanSupplier active) throws IOException {
        var mavenArgs = plan.command();
        var artifacts = Files.createDirectory(runDirectory.resolve("artifacts").resolve(label));
        var snapshot = Files.createDirectories(runDirectory.resolve("builds").resolve(label));
        files.snapshot(snapshot);
        final var name = name(id, label);
        var inspect = docker(snapshot, active, "image", "inspect", config.mavenImage(), "--format", "{{.Id}}");
        if (!inspect.ok() || !inspect.text().strip().matches("sha256:[0-9a-f]{64}")) {
            Files.write(artifacts.resolve("docker-image.log"), inspect.bytes());
            throw new IOException("本机 Docker 未就绪或未取得指定的 Maven 镜像。");
        }
        String image = inspect.text().strip();
        var command = new ArrayList<>(DOCKER);
        command.addAll(List.of("run", "--rm", "--pull=never", "--name", name, "--network=none", "--cap-drop=ALL",
                "--security-opt=no-new-privileges", "--pids-limit=256", "--memory=2g", "--cpus=2", "--read-only",
                "--tmpfs", "/tmp:rw,nosuid,nodev", "--mount", "type=bind,source=" + snapshot + ",target=/project",
                "--mount", "type=bind,source=" + workspaces.mavenRepository() + ",target=/offline-repo,readonly",
                "--workdir", "/project", "--env", "HOME=/tmp", "--entrypoint", "mvn", image));
        command.addAll(mavenArgs.subList(1, mavenArgs.size()));
        json.writeValue(artifacts.resolve("launch.json").toFile(), Map.of("container", name, "imageId", image,
                "requestedAt", Instant.now().toString(), "patchSha256", patchSha, "dockerArguments", command,
                "mavenArguments", mavenArgs, "network", "none"));

        var log = artifacts.resolve("maven.log");
        LocalProcess.Result result;
        try {
            result = LocalProcess.run(command, snapshot, config.buildTimeout(), MAX_ARTIFACT, Map.of(), active, log);
        } finally {
            cleanup(name);
        }
        if (!Files.exists(log) || Files.size(log) == 0) Files.write(log, result.bytes());
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("command", mavenArgs);
        observed.put("startedAt", result.startedAt().toString());
        observed.put("endedAt", result.endedAt().toString());
        if (result.exitCode() != null) observed.put("exitCode", result.exitCode());
        json.writeValue(artifacts.resolve("command.json").toFile(), observed);
        json.writeValue(artifacts.resolve("outcome.json").toFile(), Map.of("end", result.end().name(),
                "mavenLogTruncated", result.truncated(), "logByteLimit", MAX_ARTIFACT));

        List<Path> xml = new ArrayList<>();
        String note = "";
        try {
            collectReports(snapshot, artifacts, xml);
        } catch (IOException e) {
            note = e.getMessage();
        }
        if (result.truncated()) note = (note + " Maven 日志超过 2 MiB，已保留开头。 ").strip();
        return new Build(label, patchSha, image, artifacts, result, List.copyOf(xml), note, mavenArgs);
    }

    private static void collectReports(Path snapshot, Path artifacts, List<Path> saved) throws IOException {
        var folder = snapshot.resolve("target/surefire-reports");
        if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS) || !folder.toRealPath().equals(folder)) {
            throw new IOException("Surefire 输出目录指向了构建副本之外或经过链接。");
        }
        long total = Files.size(artifacts.resolve("maven.log")) + Files.size(artifacts.resolve("command.json"));
        try (var children = Files.list(folder)) {
            for (var item : children.filter(p -> p.getFileName().toString().matches("TEST-[A-Za-z0-9_.\\-$]{1,230}\\.xml"))
                    .sorted().toList()) {
                if (!Files.isRegularFile(item, LinkOption.NOFOLLOW_LINKS) || !item.toRealPath().equals(item)) {
                    throw new IOException("Surefire XML 指向了其他位置。");
                }
                long size = Files.size(item);
                total += size;
                if (size < 1 || size > MAX_ARTIFACT || total > MAX_TOTAL || saved.size() >= 40) {
                    throw new IOException("Surefire 原件超过工程证据支持的文件数或字节上限。");
                }
                saved.add(Files.copy(item, artifacts.resolve(item.getFileName())));
            }
        }
    }

    void recover(UUID id) {
        cleanup(name(id, "baseline"));
        cleanup(name(id, "baseline-with-new-tests"));
        for (int attempt = 1; attempt <= 3; attempt++) cleanup(name(id, "candidate-" + attempt));
    }

    private void cleanup(String name) {
        try {
            docker(workspaces.data(), LocalProcess.ACTIVE, "rm", "-f", name);
        } catch (IOException ignored) {
            // A finished --rm container is already gone. Startup recovery retries known run containers.
        }
    }

    private static LocalProcess.Result docker(Path directory, BooleanSupplier active, String... args) throws IOException {
        var command = new ArrayList<>(DOCKER);
        command.addAll(List.of(args));
        return LocalProcess.run(command, directory, DOCKER_CONTROL_TIMEOUT, 24_000, Map.of(), active, null);
    }

    private static String name(UUID id, String label) { return "insightops-exec-" + id + "-" + label; }
}
