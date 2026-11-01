package dev.friendai.debug;

import dev.friendai.foundation.diagnostics.DiagnosticBundleWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * Owns the diagnostic writer thread. A bundle is collected on the server thread and written here, so a slow disk
 * never stalls the server; {@link #close()} drains an in-flight bundle before the server stops.
 */
public final class DiagnosticService implements AutoCloseable {
    private final Path root;
    private final ExecutorService executor;

    public DiagnosticService(Path gameDir) {
        this.root = gameDir.resolve("logs").resolve("friendai").resolve("diagnostics");
        this.executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "friendai-diagnostics");
            thread.setDaemon(true);
            return thread;
        });
    }

    public Path root() {
        return root;
    }

    public CompletableFuture<Path> write(Map<String, String> files, UnaryOperator<String> redactor) {
        Map<String, String> snapshot = Map.copyOf(files);
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return DiagnosticBundleWriter.write(root, snapshot, redactor);
                    } catch (IOException error) {
                        throw new CompletionException(error);
                    }
                },
                executor);
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
