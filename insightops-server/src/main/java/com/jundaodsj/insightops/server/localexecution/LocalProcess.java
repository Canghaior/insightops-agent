package com.jundaodsj.insightops.server.localexecution;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

final class LocalProcess {
    static final BooleanSupplier ACTIVE = () -> true;
    static final boolean WINDOWS = java.io.File.separatorChar == '\\';
    private static final Set<String> HOST_ENV = Set.of("PATH", "Path", "SystemRoot", "SYSTEMROOT", "WINDIR",
            "TEMP", "TMP", "HOME", "USERPROFILE", "LOCALAPPDATA", "APPDATA", "PATHEXT");

    enum End { EXITED, TIMEOUT, CANCELLED }

    record Result(Instant startedAt, Instant endedAt, Integer exitCode, End end, byte[] bytes, boolean truncated) {
        String text() { return new String(bytes, StandardCharsets.UTF_8); }
        boolean ok() { return end == End.EXITED && Integer.valueOf(0).equals(exitCode) && !truncated; }
    }

    private LocalProcess() {}

    static Result run(List<String> args, Path directory, Duration timeout, int maxBytes,
                      Map<String, String> options, BooleanSupplier active, Path log) throws IOException {
        var builder = new ProcessBuilder(args).directory(directory.toFile()).redirectErrorStream(true);
        var environment = builder.environment();
        environment.keySet().removeIf(name -> !HOST_ENV.contains(name));
        environment.putAll(options);
        var started = Instant.now();
        Process process = builder.start();
        try {
            process.getOutputStream().close();
            return collect(process, started, timeout, maxBytes, active, log);
        } finally {
            if (process.isAlive()) stop(process);
        }
    }

    private static Result collect(Process process, Instant started, Duration timeout, int maxBytes,
                                  BooleanSupplier active, Path log) throws IOException {
        var output = new ByteArrayOutputStream();
        var overflow = new java.util.concurrent.atomic.AtomicBoolean();
        var captureError = new AtomicReference<IOException>();
        Thread reader = Thread.startVirtualThread(() -> {
            try (var input = process.getInputStream();
                 var file = log == null ? OutputStream.nullOutputStream() : Files.newOutputStream(log)) {
                byte[] buffer = new byte[8192];
                for (int size; (size = input.read(buffer)) != -1;) {
                    int keep = Math.min(size, maxBytes - output.size());
                    output.write(buffer, 0, keep);
                    file.write(buffer, 0, keep);
                    if (keep < size) overflow.set(true);
                }
            } catch (IOException e) {
                captureError.set(e);
            }
        });
        End end = End.EXITED;
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (!process.waitFor(120, TimeUnit.MILLISECONDS)) {
                if (!active.getAsBoolean()) { end = End.CANCELLED; break; }
                if (System.nanoTime() > deadline) { end = End.TIMEOUT; break; }
            }
            if (end != End.EXITED) stop(process);
            if (!reader.join(Duration.ofSeconds(3))) {
                process.getInputStream().close();
                if (!reader.join(Duration.ofSeconds(2))) throw new IOException("进程输出未能收口。");
            }
        } catch (InterruptedException e) {
            stop(process);
            Thread.currentThread().interrupt();
            throw new IOException("执行线程已中断。", e);
        }
        if (end == End.EXITED && captureError.get() != null) throw captureError.get();
        return new Result(started, Instant.now(), end == End.EXITED ? process.exitValue() : null,
                end, output.toByteArray(), overflow.get());
    }

    private static void stop(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
