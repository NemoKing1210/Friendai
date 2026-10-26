package dev.friendai.skin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkinStoreTest {
    private static final byte[] PNG_A = {1, 2, 3, 4, 5};
    private static final byte[] PNG_B = {9, 8, 7};

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
    void clearRemovesBothFiles() throws IOException {
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
    void corruptMetadataKeepsTheSkinWithDefaultsAndABackup() throws IOException {
        SkinStore store = new SkinStore(dir);
        UUID owner = UUID.randomUUID();
        store.save(owner, PNG_A, true);
        Files.writeString(dir.resolve(owner + ".json"), "{ not json");

        SkinStore.Stored loaded = store.load(owner).orElseThrow();

        assertArrayEquals(PNG_A, loaded.png());
        assertFalse(loaded.slim());
        assertTrue(Files.exists(dir.resolve(owner + ".json.bak")));
    }

    @Test
    void pngByHashOnlyAnswersForTheCurrentHash() {
        SkinStore store = new SkinStore(dir);
        UUID owner = UUID.randomUUID();
        store.save(owner, PNG_A, false);

        assertArrayEquals(PNG_A, store.pngByHash(owner, SkinHash.of(PNG_A)).orElseThrow());
        assertTrue(store.pngByHash(owner, SkinHash.of(PNG_B)).isEmpty());
        assertTrue(store.pngByHash(UUID.randomUUID(), SkinHash.of(PNG_A)).isEmpty());
    }
}
