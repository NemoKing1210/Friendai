package dev.friendai.infrastructure.persistence.appearance;

import dev.friendai.features.appearance.*;
import dev.friendai.infrastructure.config.ConfigStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;

/**
 * One skin per owner on disk: {@code <uuid>.png} holds the canonical image and {@code <uuid>.json} the arm model.
 * The metadata goes through {@link ConfigStore}, so a corrupt file is backed up and replaced by the defaults
 * while the image itself survives. Callers validate the PNG with {@link SkinImage} first.
 */
public final class SkinStore {
    /** On-disk metadata. Gson POJO; keep field names stable for old saves. */
    public static final class Meta {
        public int version = 1;
        public boolean slim;
    }

    public record Stored(String hash, boolean slim, byte[] png) {}

    private final Path dir;

    public SkinStore(Path dir) {
        this.dir = dir;
    }

    public synchronized void save(UUID owner, byte[] png, boolean slim) {
        try {
            Files.createDirectories(dir);
            Path target = png(owner);
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(temp, png);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Meta meta = new Meta();
        meta.slim = slim;
        metaStore(owner).save(meta);
    }

    public synchronized Optional<Stored> load(UUID owner) {
        Path png = png(owner);
        if (!Files.exists(png)) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(png);
            boolean slim = Files.exists(meta(owner)) && metaStore(owner).load().slim;
            return Optional.of(new Stored(SkinHash.of(bytes), slim, bytes));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The owner's PNG, but only when {@code hash} is the hash of the skin currently stored. */
    public synchronized Optional<byte[]> pngByHash(UUID owner, String hash) {
        return load(owner).filter(stored -> stored.hash().equals(hash)).map(Stored::png);
    }

    public synchronized void clear(UUID owner) {
        try {
            Files.deleteIfExists(png(owner));
            Files.deleteIfExists(meta(owner));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path png(UUID owner) {
        return dir.resolve(owner + ".png");
    }

    private Path meta(UUID owner) {
        return dir.resolve(owner + ".json");
    }

    private ConfigStore<Meta> metaStore(UUID owner) {
        return new ConfigStore<>(meta(owner), Meta.class, Meta::new, 1, (json, from) -> json);
    }
}
