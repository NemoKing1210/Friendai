package dev.friendai.task.cooking;

import dev.friendai.features.crafting.Station;
import net.minecraft.core.BlockPos;

/** A furnace this companion loaded, remembered until the result is collected. */
public record FurnaceJob(BlockPos pos, Station station, String input, String output, int loaded, boolean placedByMe) {}
