package dev.friendai.voice.tts;

import dev.friendai.text.UserFacingException;
import dev.friendai.text.UserMessage;
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
import java.util.concurrent.CompletableFuture;
import java.util.function.DoubleConsumer;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

/** Downloads local voices, verifies size and SHA-256, and extracts them so nothing half-written is visible. */
public final class VoiceModelManager {
    private final Path dir;
    private final HttpClient http;

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
        if (isInstalled(model)) {
            return CompletableFuture.completedFuture(dir(model));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(model.url())).GET().build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).thenApplyAsync(response -> {
            if (response.statusCode() != 200) {
                closeQuietly(response.body());
                throw new UserFacingException(UserMessage.of("friendai.voice.tts.error.network"));
            }
            return save(model, response.body(), progress);
        });
    }

    private Path save(VoiceModel model, InputStream body, DoubleConsumer progress) {
        Path archive = dir.resolve(model.id() + ".tar.bz2");
        Path part = dir.resolve(model.id() + ".tar.bz2.part");
        Path partDir = dir.resolve(model.id() + ".part");
        try (InputStream in = body) {
            Files.createDirectories(dir);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (OutputStream out = Files.newOutputStream(
                    part, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    digest.update(buffer, 0, n);
                    total += n;
                    progress.accept(Math.min(1.0, (double) total / Math.max(1L, model.size())));
                }
            }
            if (total != model.size() || !HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(model.sha256())) {
                throw corrupt();
            }
            Files.move(part, archive, StandardCopyOption.REPLACE_EXISTING);
            deleteRecursively(partDir);
            extract(model, archive, partDir);
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

    private static void extract(VoiceModel model, Path archive, Path partDir) throws IOException {
        Files.createDirectories(partDir);
        try (InputStream in = Files.newInputStream(archive);
                BZip2CompressorInputStream bz = new BZip2CompressorInputStream(in);
                TarArchiveInputStream tar = new TarArchiveInputStream(bz)) {
            TarArchiveEntry entry;
            // Piper archives wrap everything in one directory ("vits-piper-<id>/"); flatten it so the voice
            // directory itself holds the model, the tokens and the data dir where the catalog expects them.
            String root = null;
            boolean first = true;
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
                    Files.copy(tar, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
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
