package dev.friendai.infrastructure.persistence.cartography;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.cartography.TerrainCell;
import dev.friendai.features.cartography.TerrainGrid;
import dev.friendai.features.cartography.TerrainRoad;
import dev.friendai.features.cartography.TerrainSite;
import dev.friendai.infrastructure.persistence.OwnerStoreWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TerrainStoreTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    @TempDir
    Path dir;

    @Test
    void aMissingRecordIsEmptyAndSaveRoundTrips() throws Exception {
        TerrainGrid saved = new TerrainGrid();
        saved.setGeneration(42);
        saved.addSite(new TerrainSite("minecraft:overworld", 4, -7, "village", "oak hollow", 1234));
        saved.addRoad(new TerrainRoad("minecraft:overworld", 4, -7, 40, 12, "path", 1235));
        saved.observe(new TerrainCell(
                new TerrainCell.CellKey("minecraft:overworld", 4, -7),
                "minecraft:forest",
                70,
                true,
                "minecraft:village",
                1234));
        try (var writer = new OwnerStoreWriter();
                var store = new TerrainStore(dir, writer)) {
            assertTrue(store.grid(OWNER).cells().isEmpty());
            assertFalse(Files.exists(dir.resolve(OWNER + ".json")));
            store.save(OWNER, saved);
            assertEquals(1, store.status(OWNER).currentRevision());
        }
        try (var writer = new OwnerStoreWriter();
                var reopened = new TerrainStore(dir, writer)) {
            TerrainGrid loaded = reopened.grid(OWNER);
            assertEquals(1, loaded.size());
            TerrainCell cell = loaded.cells().getFirst();
            assertEquals("minecraft:forest", cell.biome());
            assertTrue(cell.water());
            assertEquals(1234, cell.lastSeenTick());
            assertEquals(42, loaded.generation());
            assertEquals("oak hollow", loaded.sites().getFirst().name());
            assertEquals("path", loaded.roads().getFirst().kind());
        }
    }

    @Test
    void aNewerRecordIsLeftUnread() throws Exception {
        Files.writeString(dir.resolve(OWNER + ".json"), "{\"formatVersion\":3,\"revision\":1,\"payload\":null}");
        try (var writer = new OwnerStoreWriter();
                var store = new TerrainStore(dir, writer)) {
            assertTrue(store.grid(OWNER).cells().isEmpty());
        }
    }
}
