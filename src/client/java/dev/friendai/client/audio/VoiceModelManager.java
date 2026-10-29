package dev.friendai.client.audio;

import dev.friendai.features.speech.tts.VoiceModel;
import dev.friendai.foundation.text.UserFacingException;
import dev.friendai.foundation.text.UserMessage;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleConsumer;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

/** Downloads local voices, verifies size and SHA-256, and extracts them so nothing half-written is visible. */
public final class VoiceModelManager {
    /**
     * The two phases of an install, reported from a background thread. The byte download is quick on a fast
     * connection; verifying and unpacking the archive takes much longer, so a caller that only listens to
     * {@link #downloading} would show "100%" and then stay silent for minutes.
     */
    public interface DownloadStage {
        /** @param fraction bytes received, in [0, 1] */
        void downloading(double fraction);

        /** @param fraction archive consumed while verifying and unpacking, in [0, 1] */
        void installing(double fraction);
    }

    private final Path dir;
    private final HttpClient http;
    /** One running install per voice; a second caller joins it instead of deleting its staging directory. */
    private final Map<String, CompletableFuture<Path>> inFlight = new ConcurrentHashMap<>();

    private final AtomicLong operations = new AtomicLong();

    public VoiceModelManager(Path dir, HttpClient http) {
        this.dir = dir;
        this.http = http;
    }

    public Path dir(VoiceModel model) {
        return dir.resolve(model.id());
    }

    public boolean isInstalled(VoiceModel model) {
        Path voice = dir(model);
        return Files.isRegularFile(voice.resolve(model.modelFile()))
                && Files.isRegularFile(voice.resolve(model.tokensFile()))
                && Files.isDirectory(voice.resolve(model.dataDir()));
    }

    /** @param progress called with a fraction in [0, 1] as bytes arrive, from a background thread */
    public CompletableFuture<Path> download(VoiceModel model, DoubleConsumer progress) {
        return download(model, new DownloadStage() {
            @Override
            public void downloading(double fraction) {
                progress.accept(fraction);
            }

            @Override
            public void installing(double fraction) {
                // A caller that only asked for download bytes has nothing to show for this phase.
            }
        });
    }

    /**
     * Downloads and installs one voice. A second call for the same voice joins the running operation and completes
     * with it; cancelling either caller's future does not cancel the shared install, and progress is not replayed.
     *
     * @param stage called with the download and then the unpacking fraction, from a background thread
     */
    public CompletableFuture<Path> download(VoiceModel model, DownloadStage stage) {
        CompletableFuture<Path> shared;
        synchronized (this) {
            if (isInstalled(model)) {
                return CompletableFuture.completedFuture(dir(model));
            }
            shared = inFlight.get(model.id());
            if (shared == null || shared.isDone()) {
                CompletableFuture<Path> created = start(model, stage);
                inFlight.put(model.id(), created);
                created.whenComplete((ignored, error) -> inFlight.remove(model.id(), created));
                shared = created;
            }
        }
        // Each caller owns a plain relay future: cancelling it never cancels the shared operation or another caller.
        CompletableFuture<Path> caller = new CompletableFuture<>();
        shared.whenComplete((result, error) -> {
            if (error != null) caller.completeExceptionally(error);
            else caller.complete(result);
        });
        return caller;
    }

    private CompletableFuture<Path> start(VoiceModel model, DownloadStage stage) {
        long operation = operations.incrementAndGet();
        Path archive = dir.resolve(model.id() + "." + operation + ".tar.bz2");
        Path part = dir.resolve(model.id() + "." + operation + ".tar.bz2.part");
        Path partDir = dir.resolve(model.id() + "." + operation + ".part");
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(model.url())).GET().build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenApplyAsync(response -> {
                    if (response.statusCode() != 200) {
                        closeQuietly(response.body());
                        throw new UserFacingException(UserMessage.of("friendai.voice.tts.error.network"));
                    }
                    return save(model, response.body(), stage, part, archive, partDir);
                });
    }

    private Path save(VoiceModel model, InputStream body, DownloadStage stage, Path part, Path archive, Path partDir) {
        try (InputStream in = body) {
            Files.createDirectories(dir);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (OutputStream out =
                    Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[64 * 1024];
                long limit = model.size();
                while (total <= limit) {
                    // One byte past the declared size is enough to detect a longer body without reading it.
                    int allowed = (int) Math.min(buffer.length, limit + 1 - total);
                    int n = in.read(buffer, 0, allowed);
                    if (n < 0) break;
                    total += n;
                    if (total > limit) break;
                    out.write(buffer, 0, n);
                    digest.update(buffer, 0, n);
                    stage.downloading(Math.min(1.0, (double) total / Math.max(1L, limit)));
                }
            }
            if (total != model.size()
                    || !HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(model.sha256())) {
                throw corrupt();
            }
            Files.move(part, archive, StandardCopyOption.REPLACE_EXISTING);
            deleteRecursively(partDir);
            extract(model, archive, partDir, stage);
            validate(model, partDir);
            deleteRecursively(dir(model));
            move(partDir, dir(model));
            Files.deleteIfExists(archive);
            return dir(model);
        } catch (UserFacingException e) {
            cleanup(part, archive, partDir);
            throw e;
        } catch (IOException | NoSuchAlgorithmException e) {
            cleanup(part, archive, partDir);
            throw new UserFacingException(UserMessage.of("friendai.voice.tts.error.network"));
        }
    }

    private static void extract(VoiceModel model, Path archive, Path partDir, DownloadStage stage) throws IOException {
        Files.createDirectories(partDir);
        long archiveSize = Math.max(1L, Files.size(archive));
        try (CountingInputStream in = new CountingInputStream(Files.newInputStream(archive));
                BZip2CompressorInputStream bz = new BZip2CompressorInputStream(in);
                TarArchiveInputStream tar = new TarArchiveInputStream(bz)) {
            TarArchiveEntry entry;
            // Piper archives wrap everything in one directory ("vits-piper-<id>/"); flatten it so the voice
            // directory itself holds the model, the tokens and the data dir where the catalog expects them.
            String root = null;
            boolean first = true;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = tar.getNextEntry()) != null) {
                String name = entry.getName();
                if (first) {
                    first = false;
                    if (entry.isDirectory() && name.endsWith("/") && name.indexOf('/') == name.length() - 1) {
                        root = name;
                        continue;
                    }
                }
                if (root != null && name.startsWith(root)) {
                    name = name.substring(root.length());
                    if (name.isEmpty()) {
                        continue;
                    }
                }
                Path target = partDir.resolve(name).normalize();
                if (!target.startsWith(partDir)) {
                    throw corrupt();
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (OutputStream out = Files.newOutputStream(
                            target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        int n;
                        while ((n = tar.read(buffer)) > 0) {
                            out.write(buffer, 0, n);
                            stage.installing(Math.min(1.0, (double) in.count() / archiveSize));
                        }
                    }
                }
            }
        }
        stage.installing(1.0);
    }

    /** Counts compressed bytes read from the archive, so unpacking reports real progress. */
    private static final class CountingInputStream extends FilterInputStream {
        private long count;

        private CountingInputStream(InputStream in) {
            super(in);
        }

        long count() {
            return count;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                count++;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                count += read;
            }
            return read;
        }
    }

    private static void validate(VoiceModel model, Path partDir) {
        if (!Files.isRegularFile(partDir.resolve(model.modelFile()))
                || !Files.isRegularFile(partDir.resolve(model.tokensFile()))
                || !Files.isDirectory(partDir.resolve(model.dataDir()))) {
            throw corrupt();
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }

    private void cleanup(Path part, Path archive, Path partDir) {
        try {
            Files.deleteIfExists(part);
            Files.deleteIfExists(archive);
            deleteRecursively(partDir);
        } catch (IOException ignored) {
            // best effort: the next download starts from a clean name
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path entry : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    private static UserFacingException corrupt() {
        return new UserFacingException(UserMessage.of("friendai.voice.tts.error.model_corrupt"));
    }
}
