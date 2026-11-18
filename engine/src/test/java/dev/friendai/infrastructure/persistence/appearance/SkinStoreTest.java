package dev.friendai.infrastructure.persistence.appearance;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.appearance.SkinHash;
import dev.friendai.features.appearance.SkinImage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkinStoreTest {
    private static final byte[] PNG_A = png(0xff11aa33);
    private static final byte[] PNG_B = png(0xff994411);

    @TempDir
    Path dir;

    @Test
    void saveThenLoadRoundTrips() {
        SkinStore store = new SkinStore(dir.resolve("skins"));
        UUID owner = UUID.randomUUID();

        store.save(owner, PNG_A, true);
        SkinStore.Stored loaded = store.load(owner).orElseThrow();

        assertArrayEquals(PNG_A, loaded.png());
        assertTrue(loaded.slim());
        assertEquals(SkinHash.of(PNG_A), loaded.hash());
    }

    @Test
    void loadMissingIsEmpty() {
        assertTrue(new SkinStore(dir).load(UUID.randomUUID()).isEmpty());
    }

    @Test
    void clearRemovesRevision() throws IOException {
        SkinStore store = new SkinStore(dir);
        UUID owner = UUID.randomUUID();
        store.save(owner, PNG_A, false);
        store.clear(owner);

        assertTrue(store.load(owner).isEmpty());
        try (var files = Files.list(dir)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void secondSaveReplacesTheFirst() {
        SkinStore store = new SkinStore(dir);
        UUID owner = UUID.randomUUID();
        store.save(owner, PNG_A, true);
        store.save(owner, PNG_B, false);
        SkinStore.Stored loaded = store.load(owner).orElseThrow();

        assertArrayEquals(PNG_B, loaded.png());
        assertFalse(loaded.slim());
    }

    @Test
    void partialMetadataWriteKeepsThePreviousPair() {
        UUID owner = UUID.randomUUID();
        SkinStore store = new SkinStore(dir);
        store.save(owner, PNG_A, true);
        SkinStore failing = new SkinStore(dir, (file, bytes) -> {
            Files.write(file, new byte[] {bytes[0]});
            throw new IOException("staged metadata write failed");
        });

        assertThrows(UncheckedIOException.class, () -> failing.save(owner, PNG_B, false));
        SkinStore.Stored loaded = store.load(owner).orElseThrow();
        assertTrue(loaded.slim());
        assertArrayEquals(PNG_A, loaded.png());
        assertEquals(SkinHash.of(PNG_A), loaded.hash());
    }

    @Test
    void legacyImageAndSlimFlagLoadAndReset() throws IOException {
        UUID owner = UUID.randomUUID();
        Files.write(dir.resolve(owner + ".png"), PNG_A);
        Files.writeString(dir.resolve(owner + ".json"), "{ \"version\": 1, \"slim\": true }");
        SkinStore store = new SkinStore(dir);

        SkinStore.Stored loaded = store.load(owner).orElseThrow();
        assertArrayEquals(PNG_A, loaded.png());
        assertEquals(SkinHash.of(PNG_A), loaded.hash());
        assertTrue(loaded.slim());

        store.clear(owner);
        assertTrue(new SkinStore(dir).load(owner).isEmpty());
        assertFalse(Files.exists(dir.resolve(owner + ".png")));
    }

    @Test
    void corruptLegacyMetadataKeepsTheSkinWithDefaultsAndABackup() throws IOException {
        UUID owner = UUID.randomUUID();
        Files.write(dir.resolve(owner + ".png"), PNG_A);
        Files.writeString(dir.resolve(owner + ".json"), "{ not json");

        SkinStore.Stored loaded = new SkinStore(dir).load(owner).orElseThrow();
        assertArrayEquals(PNG_A, loaded.png());
        assertFalse(loaded.slim());
        assertTrue(Files.exists(dir.resolve(owner + ".json.bak")));
    }

    @Test
    void malformedAndOversizedLegacyPngAreRejected() throws IOException {
        UUID owner = UUID.randomUUID();
        Path file = dir.resolve(owner + ".png");
        SkinStore store = new SkinStore(dir);

        Files.write(file, new byte[] {1, 2, 3});
        assertTrue(store.load(owner).isEmpty());
        Files.write(file, new byte[SkinImage.MAX_CANONICAL + 1]);
        assertTrue(store.load(owner).isEmpty());
    }

    @Test
    void corruptedRevisionCannotFallBackToAnOlderLegacyPng() throws IOException {
        UUID owner = UUID.randomUUID();
        Files.write(dir.resolve(owner + ".png"), PNG_A);
        SkinStore store = new SkinStore(dir);
        store.save(owner, PNG_B, true);

        Path revision = dir.resolve(owner + ".skin");
        byte[] bytes = Files.readAllBytes(revision);
        bytes[5] = bytes[5] == 'a' ? (byte) 'b' : (byte) 'a';
        Files.write(revision, bytes);
        assertTrue(store.load(owner).isEmpty());

        Files.write(revision, new byte[SkinImage.MAX_CANONICAL + 100]);
        assertTrue(store.load(owner).isEmpty());
    }

    private static byte[] png(int argb) {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(8, 8, argb);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(image, "png", bytes);
            return ((SkinImage.Valid) SkinImage.validate(bytes.toByteArray())).png();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
