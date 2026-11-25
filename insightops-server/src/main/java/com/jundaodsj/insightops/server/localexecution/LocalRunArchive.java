package com.jundaodsj.insightops.server.localexecution;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class LocalRunArchive {
    private static final long MAX_TOTAL_BYTES = 56L * 1024 * 1024;
    private static final LocalDateTime ZIP_TIME = LocalDateTime.of(1980, 1, 1, 0, 0);

    private record Entry(String path, long size, String sha256) {}
    private record Manifest(String schema, UUID runId, List<Entry> files) {}
    private final Path root;
    private final ObjectMapper json;

    LocalRunArchive(Path data, ObjectMapper json) throws IOException {
        root = Files.createDirectories(data.resolve("runs"));
        this.json = json;
    }

    Path create(UUID id) throws IOException {
        var path = Files.createDirectory(root.resolve(id.toString()));
        Files.createDirectory(path.resolve("artifacts"));
        return path;
    }

    byte[] patch(LocalRunStore.Run run) throws IOException {
        if (run.patchSha256() == null) return new byte[0];
        var file = artifacts(run.id()).resolve("changes.patch");
        if (!ordinary(file) || Files.size(file) > LocalWorkspaces.MAX_PATCH) {
            throw new IOException("本次实际补丁原件当前不可用。");
        }
        byte[] data = Files.readAllBytes(file);
        if (!LocalWorkspaces.sha(data).equals(run.patchSha256())) throw new IOException("本次补丁的 SHA-256 不一致。");
        return data;
    }

    byte[] zip(LocalRunStore.Detail detail) throws IOException {
        var base = artifacts(detail.run().id());
        List<Path> files = List.of();
        if (Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) {
            try (var entries = Files.walk(base)) {
                files = entries.filter(LocalRunArchive::regular).sorted().toList();
            }
        }
        var stream = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(stream)) {
            List<Entry> manifest = new ArrayList<>();
            byte[] trace = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(detail);
            long total = trace.length;
            item(zip, "execution.json", trace, manifest);
            for (Path path : files) {
                if (!ordinary(path) || (total += Files.size(path)) > MAX_TOTAL_BYTES) {
                    throw new IOException("运行原件超出交接包大小或路径范围。");
                }
                String name = "artifacts/" + base.relativize(path).toString().replace('\\', '/');
                item(zip, name, Files.readAllBytes(path), manifest);
            }
            item(zip, "manifest.json", json.writerWithDefaultPrettyPrinter().writeValueAsBytes(
                    new Manifest("insightops.local-execution/v1", detail.run().id(), manifest)), null);
        }
        return stream.toByteArray();
    }

    private Path artifacts(UUID id) { return root.resolve(id.toString()).resolve("artifacts"); }

    private static boolean regular(Path file) { return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS); }

    private static boolean ordinary(Path file) throws IOException { return regular(file) && file.toRealPath().equals(file); }

    private static void item(ZipOutputStream zip, String name, byte[] bytes, List<Entry> manifest) throws IOException {
        var entry = new ZipEntry(name);
        entry.setTimeLocal(ZIP_TIME);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
        if (manifest != null) manifest.add(new Entry(name, bytes.length, LocalWorkspaces.sha(bytes)));
    }
}
