package com.jundaodsj.insightops.infrastructure.knowledge;

import com.jundaodsj.insightops.knowledge.application.KnowledgeFileStorage;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class LocalKnowledgeFileStorage implements KnowledgeFileStorage {
    private static final Pattern STORAGE_KEY = Pattern.compile("[0-9a-f-]{36}\\.bin");
    private final Path directory;

    public LocalKnowledgeFileStorage(KnowledgeUploadProperties properties) {
        this.directory = Path.of(properties.getDirectory()).toAbsolutePath().normalize();
    }

    @Override
    public StoredFile store(UUID uploadId, InputStream input, long maximumBytes) throws IOException {
        Files.createDirectories(directory);
        String storageKey = uploadId + ".bin";
        Path target = resolve(storageKey);
        Path temporary = resolve(uploadId + ".partial");
        MessageDigest digest = sha256();
        long total = 0;
        try (input; OutputStream output = Files.newOutputStream(temporary)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > maximumBytes) throw new IOException("Upload exceeds configured byte limit");
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        } catch (IOException exception) {
            Files.deleteIfExists(temporary);
            throw exception;
        }
        if (total == 0) {
            Files.deleteIfExists(temporary);
            throw new IOException("Upload is empty");
        }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return new StoredFile(storageKey, total, HexFormat.of().formatHex(digest.digest()));
    }

    @Override
    public InputStream open(String storageKey) throws IOException {
        return Files.newInputStream(resolve(storageKey));
    }

    @Override
    public void delete(String storageKey) throws IOException {
        Files.deleteIfExists(resolve(storageKey));
    }

    private Path resolve(String storageKey) {
        if (storageKey == null || !(STORAGE_KEY.matcher(storageKey).matches()
                || storageKey.matches("[0-9a-f-]{36}\\.partial"))) {
            throw new IllegalArgumentException("Invalid knowledge upload storage key");
        }
        Path result = directory.resolve(storageKey).normalize();
        if (!result.getParent().equals(directory)) throw new IllegalArgumentException("Storage key escapes upload root");
        return result;
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
    }
}
