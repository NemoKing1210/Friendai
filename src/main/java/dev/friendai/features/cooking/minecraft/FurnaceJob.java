package dev.friendai.features.cooking.minecraft;

import dev.friendai.features.crafting.Station;
import net.minecraft.core.BlockPos;

/** A furnace this companion loaded, remembered until the result is collected; the dimension is part of its identity. */
public record FurnaceJob(
        BlockPos pos, String dimension, Station station, String input, String output, int loaded, boolean placedByMe) {}
