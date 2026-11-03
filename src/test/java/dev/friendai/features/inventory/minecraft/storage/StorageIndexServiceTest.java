package dev.friendai.features.inventory.minecraft.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.storage.StorageLimits;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class StorageIndexServiceTest {
    @Test
    void aDoubleChestCanonicalizesToTheLowerHalf() {
        var origin = new BlockPos(6, 64, 6);
        assertEquals(
                new BlockPos(5, 64, 6),
                StorageIndexService.canonicalChest(origin, pos -> pos.equals(new BlockPos(5, 64, 6))));
        assertEquals(origin, StorageIndexService.canonicalChest(origin, pos -> false));
    }

    @Test
    void unavailableFindsNothing() {
        assertTrue(StorageAccess.UNAVAILABLE.find(null, Set.of("minecraft:iron_ingot"), 1, 0)
                .isEmpty());
    }

    @Test
    void limitsStayInsideThePublicRanges() {
        var limits = StorageLimits.DEFAULTS;
        assertEquals(256, limits.maxContainers());
        assertEquals(64, limits.maxKindsPerContainer());
        assertEquals(24_000, limits.staleAfterTicks());
    }
}
