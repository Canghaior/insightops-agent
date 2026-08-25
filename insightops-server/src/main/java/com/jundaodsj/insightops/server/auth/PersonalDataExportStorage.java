package com.jundaodsj.insightops.server.auth;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class PersonalDataExportStorage {
    private final Path root;
    public PersonalDataExportStorage(PersonalDataExportProperties properties) {
        this.root = Path.of(properties.getDirectory()).toAbsolutePath().normalize();
    }
    public void write(String key, String ciphertext) {
        try {
            Files.createDirectories(root);
            Files.writeString(resolve(key), ciphertext, StandardCharsets.UTF_8);
        } catch (IOException exception) { throw new IllegalStateException("Unable to write personal export", exception); }
    }
    public String read(String key) {
        try { return Files.readString(resolve(key), StandardCharsets.UTF_8); }
        catch (IOException exception) { throw new IllegalStateException("Unable to read personal export", exception); }
    }
    public void delete(String key) {
        try { Files.deleteIfExists(resolve(key)); }
        catch (IOException exception) { throw new IllegalStateException("Unable to expire personal export", exception); }
    }
    private Path resolve(String key) {
        if (key == null || !key.matches("[0-9a-f-]{36}\\.json\\.enc")) {
            throw new IllegalArgumentException("Invalid export storage key");
        }
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) throw new IllegalArgumentException("Invalid export storage boundary");
        return path;
    }
}
