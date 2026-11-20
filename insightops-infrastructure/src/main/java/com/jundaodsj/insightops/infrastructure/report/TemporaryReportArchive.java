package com.jundaodsj.insightops.infrastructure.report;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.zip.Deflater;
import java.util.zip.ZipOutputStream;

/** Keeps a validated download off the heap. Closing the handle also disposes of the temporary file. */
public final class TemporaryReportArchive implements AutoCloseable {
    @FunctionalInterface
    public interface Contents { void write(ZipOutputStream zip) throws IOException; }

    private final FileChannel file;

    private TemporaryReportArchive(FileChannel file) {
        this.file = file;
    }

    public static TemporaryReportArchive prepare(Contents contents) throws IOException {
        var path = Files.createTempFile("insightops-handoff-", ".zip");
        FileChannel channel;
        try {
            channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE,
                    StandardOpenOption.DELETE_ON_CLOSE);
        } catch (IOException e) {
            Files.deleteIfExists(path);
            throw e;
        }
        try {
            // ZipOutputStream must finish the central directory before any HTTP headers are sent.
            var retained = new FilterOutputStream(Channels.newOutputStream(channel)) {
                @Override public void write(byte[] data, int offset, int len) throws IOException {
                    out.write(data, offset, len);
                }
                @Override public void close() throws IOException { flush(); }
            };
            try (var zip = new ZipOutputStream(retained, StandardCharsets.UTF_8)) {
                zip.setLevel(Deflater.BEST_SPEED);
                contents.write(zip);
            }
            channel.position(0);
            return new TemporaryReportArchive(channel);
        } catch (IOException | RuntimeException | Error e) {
            channel.close();
            throw e;
        }
    }

    public long size() throws IOException { return file.size(); }

    public void copyTo(OutputStream output) throws IOException {
        Channels.newInputStream(file).transferTo(output);
    }

    @Override public void close() throws IOException { file.close(); }
}
