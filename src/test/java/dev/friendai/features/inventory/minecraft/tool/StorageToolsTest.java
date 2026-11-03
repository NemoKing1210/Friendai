package dev.friendai.features.inventory.minecraft.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.inventory.minecraft.storage.StorageAccess;
import dev.friendai.features.storage.ContainerRecord;
import dev.friendai.features.storage.StorageKey;
import dev.friendai.features.storage.StoredCandidate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.Test;

class StorageToolsTest {
    @Test
    void findStoredItemRejectsAnUnknownItem() {
        var access = new FakeAccess();
        var tool = StorageTools.findStoredItem(access);
        var result = tool.execute(null, args("{\"item\":\"bogus\"}"));
        assertTrue(result.error());
        assertEquals("I don't know the item bogus", result.content());
    }

    @Test
    void findStoredItemReportsRecordsWithCoordinates() {
        var access = new FakeAccess();
        access.ids = Set.of("minecraft:iron_ingot");
        access.matches = List.of(new StoredCandidate(
                new ContainerRecord(
                        new StorageKey("minecraft:overworld", 4, 64, 0),
                        "chest",
                        90,
                        1,
                        27,
                        Map.of("minecraft:iron_ingot", 5)),
                5,
                10,
                false));
        var tool = StorageTools.findStoredItem(access);
        var result = tool.execute(null, args("{\"item\":\"minecraft:iron_ingot\"}"));
        assertFalse(result.error());
        assertTrue(result.content().contains("5 in a chest at 4 64 0"), result.content());
    }

    private static JsonObject args(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static final class FakeAccess implements StorageAccess {
        private Set<String> ids = Set.of();
        private List<StoredCandidate> matches = List.of();

        @Override
        public void forget(CompanionEntity companion, ServerLevel level, BlockPos position) {}

        @Override
        public List<ContainerRecord> records(CompanionEntity companion) {
            return List.of();
        }

        @Override
        public List<StoredCandidate> find(CompanionEntity companion, Set<String> itemIds, int minCount, long nowTick) {
            return matches;
        }

        @Override
        public Optional<BlockPos> takeTarget(CompanionEntity companion, Set<String> itemIds, int minCount) {
            return Optional.empty();
        }

        @Override
        public Optional<BlockPos> storeTarget(CompanionEntity companion) {
            return Optional.empty();
        }

        @Override
        public Set<String> itemIds(String idOrTag) {
            return idOrTag == null || idOrTag.isBlank() ? Set.of() : ids;
        }

        @Override
        public long nowTick(CompanionEntity companion) {
            return 100;
        }
    }
}
