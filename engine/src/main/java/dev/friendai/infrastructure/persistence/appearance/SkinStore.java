package dev.friendai.infrastructure.persistence.appearance;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.friendai.features.appearance.SkinHash;
import dev.friendai.features.appearance.SkinImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Stores one atomically published model and image revision per owner, with read compatibility for legacy files. */
public final class SkinStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("friendai/skin");
    private static final int MAGIC = 0x46534B01; // FSK, format version 1
    private static final byte WIDE = 0;
    private static final byte SLIM = 1;
    private static final byte RESET = 2;
    private static final int PREFIX_SIZE = Integer.BYTES + Byte.BYTES;
    private static final int HEADER_SIZE = PREFIX_SIZE + SkinHash.LENGTH;
    private static final int MAX_LEGACY_METADATA = 4096;

    public record Stored(String hash, boolean slim, byte[] png) {
        public Stored {
            png = png.clone();
        }

        @Override
        public byte[] png() {
            return png.clone();
        }
    }

    @FunctionalInterface
    interface StagedWriter {
        void write(Path file, byte[] bytes) throws IOException;
    }

    private final Path dir;
    private final StagedWriter writer;

    public SkinStore(Path dir) {
        this(dir, SkinStore::writeAndForce);
    }

    SkinStore(Path dir, StagedWriter writer) {
        this.dir = dir;
        this.writer = writer;
    }

    /** Validates an uploaded PNG and returns the published revision, or empty for a rejected image. */
    public synchronized Optional<Stored> save(UUID owner, byte[] png, boolean slim) {
        if (!(SkinImage.validate(png) instanceof SkinImage.Valid valid)) return Optional.empty();
        byte[] canonical = valid.png();
        String hash = SkinHash.of(canonical);
        byte[] revision = ByteBuffer.allocate(HEADER_SIZE + canonical.length)
                .putInt(MAGIC)
                .put(slim ? SLIM : WIDE)
                .put(hash.getBytes(StandardCharsets.US_ASCII))
                .put(canonical)
                .array();
        try {
            publish(revision(owner), revision);
            return Optional.of(new Stored(hash, slim, canonical));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    public synchronized Optional<Stored> load(UUID owner) {
        try {
            byte[] revision = readBounded(revision(owner), HEADER_SIZE + SkinImage.MAX_CANONICAL);
            return revision == null ? loadLegacy(owner) : decode(revision);
        } catch (IOException failure) {
            LOGGER.warn("Could not read companion appearance for {}", owner, failure);
            return Optional.empty();
        }
    }

    public synchronized void clear(UUID owner) {
        Path revision = revision(owner);
        Path legacyPng = dir.resolve(owner + ".png");
        try {
            if (Files.notExists(legacyPng)) {
                Files.deleteIfExists(revision);
                removeLegacyMetadata(owner);
                return;
            }
            // A reset marker hides the legacy PNG even if deleting it after publication fails.
            publish(
                    revision,
                    ByteBuffer.allocate(PREFIX_SIZE).putInt(MAGIC).put(RESET).array());
            try {
                Files.deleteIfExists(legacyPng);
                Files.deleteIfExists(revision);
            } catch (IOException failure) {
                LOGGER.warn("Could not remove old companion skin files for {}", owner, failure);
            }
            removeLegacyMetadata(owner);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private void removeLegacyMetadata(UUID owner) {
        try {
            Files.deleteIfExists(dir.resolve(owner + ".json"));
        } catch (IOException failure) {
            LOGGER.warn("Could not remove old companion skin metadata for {}", owner, failure);
        }
    }

    private Path revision(UUID owner) {
        return dir.resolve(owner + ".skin");
    }

    private Optional<Stored> loadLegacy(UUID owner) throws IOException {
        byte[] png = readBounded(dir.resolve(owner + ".png"), SkinImage.MAX_CANONICAL);
        if (png == null || !(SkinImage.validate(png) instanceof SkinImage.Valid)) return Optional.empty();
        return Optional.of(new Stored(SkinHash.of(png), legacySlim(dir.resolve(owner + ".json")), png));
    }

    private static boolean legacySlim(Path path) {
        try {
            byte[] bytes = readBounded(path, MAX_LEGACY_METADATA);
            if (bytes == null) return false;
            try {
                if (JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)) instanceof JsonObject meta) {
                    var slim = meta.get("slim");
                    return slim != null
                            && slim.isJsonPrimitive()
                            && slim.getAsJsonPrimitive().isBoolean()
                            && slim.getAsBoolean();
                }
                throw new JsonParseException("expected a skin metadata object");
            } catch (JsonParseException failure) {
                Path backup = path.resolveSibling(path.getFileName() + ".bak");
                if (Files.notExists(backup)) Files.write(backup, bytes, StandardOpenOption.CREATE_NEW);
            }
        } catch (IOException failure) {
            LOGGER.warn("Could not read old companion skin metadata at {}", path, failure);
        }
        return false;
    }

    private static Optional<Stored> decode(byte[] revision) {
        if (revision.length <= HEADER_SIZE) return Optional.empty();
        ByteBuffer data = ByteBuffer.wrap(revision);
        if (data.getInt() != MAGIC) return Optional.empty();
        byte model = data.get();
        if (model != WIDE && model != SLIM) return Optional.empty();
        byte[] hashBytes = new byte[SkinHash.LENGTH];
        data.get(hashBytes);
        String hash = new String(hashBytes, StandardCharsets.US_ASCII);
        byte[] png = new byte[data.remaining()];
        data.get(png);
        if (!hash.equals(SkinHash.of(png)) || !(SkinImage.validate(png) instanceof SkinImage.Valid)) {
            return Optional.empty();
        }
        return Optional.of(new Stored(hash, model == SLIM, png));
    }

    /** Reads at most one sentinel byte beyond the limit even if a file grows after opening it. */
    private static byte[] readBounded(Path path, int limit) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("appearance file exceeds its byte limit");
            return bytes;
        } catch (NoSuchFileException noFile) {
            return null;
        }
    }

    private void publish(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(dir);
        Path temp = Files.createTempFile(dir, target.getFileName().toString(), ".tmp");
        try {
            writer.write(temp, bytes);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void writeAndForce(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }
}
