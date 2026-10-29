package dev.friendai.features.farming.minecraft;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

/** One ripe block the companion can harvest. */
public record Harvestable(BlockPos pos, Kind kind, @Nullable Item replant) {
    public enum Kind {
        CROP,
        NETHER_WART,
        FRUIT,
        CANE,
        BERRIES
    }
}
