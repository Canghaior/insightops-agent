package com.jundaodsj.insightops.server.report;

import com.fasterxml.jackson.databind.ObjectWriter;
import com.jundaodsj.insightops.report.application.BuildEvidence.EvidenceFile;
import com.jundaodsj.insightops.report.application.BuildEvidence.Role;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Hashes the bytes actually written, including generated documents. Originals must match the saved receipt. */
final class HandoffArchiveWriter {
    private static final long MAX_UNCOMPRESSED = 384L * 1024 * 1024;
    private final ZipOutputStream zip;
    private final ObjectWriter json;
    private final List<Entry> entries = new ArrayList<>();
    private long total;

    record Entry(String path, String kind, UUID sourceId, String originalName, long size, String sha256) { }

    HandoffArchiveWriter(ZipOutputStream zip, ObjectWriter json) {
        this.zip = zip;
        this.json = json;
    }

    List<Entry> entries() { return List.copyOf(entries); }

    void json(String path, String kind, UUID sourceId, Object value) throws IOException {
        append(path, kind, sourceId, null, -1, null, stream -> json.writeValue(stream, value));
    }

    void markdown(String path, String kind, UUID sourceId, String text) throws IOException {
        append(path, kind, sourceId, null, -1, null, stream -> stream.write(text.getBytes(StandardCharsets.UTF_8)));
    }

    void original(String path, String kind, UUID id, String name, long size, String sha256, InputStream input) throws IOException {
        append(path, kind, id, name, size, sha256, input::transferTo);
    }

    @FunctionalInterface
    private interface Payload { void write(OutputStream out) throws IOException; }

    private void append(String path, String kind, UUID id, String name, long expectedSize, String expectedSha,
                        Payload payload) throws IOException {
        if (expectedSize > MAX_UNCOMPRESSED - total) throw tooLarge();
        var entry = new ZipEntry(path);
        zip.putNextEntry(entry);
        var output = new EntryOutput(expectedSize);
        payload.write(output);
        zip.closeEntry();
        String digest = HexFormat.of().formatHex(output.hash.digest());
        if (expectedSize >= 0 && (expectedSize != output.size || !digest.equalsIgnoreCase(expectedSha))) {
            throw badOriginal();
        }
        entries.add(new Entry(path, kind, id, name, output.size, digest));
    }

    private final class EntryOutput extends OutputStream {
        private final long expectedSize;
        private final MessageDigest hash;
        private long size;

        private EntryOutput(long expectedSize) {
            this.expectedSize = expectedSize;
            try { hash = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
        }
        private void count(int length) {
            if (length > MAX_UNCOMPRESSED - total) throw tooLarge();
            if (expectedSize >= 0 && length > expectedSize - size) throw badOriginal();
            size += length;
            total += length;
        }
        @Override public void write(int value) throws IOException {
            count(1);
            zip.write(value);
            hash.update((byte)value);
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            count(length);
            zip.write(bytes, offset, length);
            hash.update(bytes, offset, length);
        }
        // ObjectWriter closes its target after each entry. This stream does not own the shared ZIP.
    }

    static String originalPath(Role role, EvidenceFile file) {
        String fallback = switch (file.kind()) {
            case SUREFIRE_XML -> "original.xml";
            case MAVEN_LOG -> "maven.log";
            case COMMAND_RECORD, EXCLUSION_MANIFEST -> "record.json";
        };
        String side = role == Role.BASELINE ? "baseline" : "candidate";
        return "originals/" + side + "/" + file.id() + "/" + leaf(file.name(), fallback);
    }

    static String leaf(String name, String fallback) {
        // Keep common original names, including Surefire nested-class names. Never accept user paths as ZIP paths.
        if (name == null || !name.matches("[\\p{L}\\p{N}][\\p{L}\\p{N}._ $()\\[\\]-]{0,149}")
                || name.getBytes(StandardCharsets.UTF_8).length > 240
                || name.endsWith(".") || name.endsWith(" ")) return fallback;
        int dot = name.indexOf('.');
        String stem = (dot < 0 ? name : name.substring(0, dot)).stripTrailing().toUpperCase(Locale.ROOT);
        if (stem.matches("CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³]")) return fallback;
        return name;
    }

    private static ResponseStatusException tooLarge() {
        return new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "本版本的解压内容超过交接包 384 MiB 上限。");
    }
    private static ResponseStatusException badOriginal() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "原件的长度或 SHA-256 与入库记录不同，交接包未生成。");
    }
}
