package dev.friendai.features.inventory.minecraft.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.knowledge.ResourceKnowledge;
import dev.friendai.features.knowledge.ResourceObservation;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.features.storage.StorageLimits;
import dev.friendai.task.interact.ContainerAccess;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class StorageIndexServiceTest {
    @Test
    void aDoubleChestCanonicalizesToTheLowerHalf() {
        var origin = new BlockPos(6, 64, 6);
        assertEquals(
                new BlockPos(5, 64, 6),
                ContainerAccess.canonicalChest(origin, pos -> pos.equals(new BlockPos(5, 64, 6))));
        assertEquals(origin, ContainerAccess.canonicalChest(origin, pos -> false));
    }

    @Test
    void unavailableFindsNothing() {
        assertTrue(StorageAccess.UNAVAILABLE
                .find(null, Set.of("minecraft:iron_ingot"), 1, 0)
                .isEmpty());
    }

    @Test
    void limitsStayInsideThePublicRanges() {
        var limits = StorageLimits.DEFAULTS;
        assertEquals(256, limits.maxContainers());
        assertEquals(64, limits.maxKindsPerContainer());
        assertEquals(24_000, limits.staleAfterTicks());
    }

    @Test
    void aLegacyObservationOffersUnknownCapacityAsFreeSlots() {
        var observation = new ResourceKnowledge.ContainerObservation(
                new ResourceObservation.Location("minecraft:overworld", new Pos(1, 64, 1)),
                Map.of("minecraft:stone", 1),
                10);
        var record = StorageIndexService.toRecord(observation);
        assertEquals(27, record.slotsTotal());
        assertTrue(record.freeSlots() > 0);
    }
}
