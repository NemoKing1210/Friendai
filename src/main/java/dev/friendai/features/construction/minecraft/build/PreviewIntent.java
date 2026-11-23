package dev.friendai.features.construction.minecraft.build;

import dev.friendai.features.construction.blueprint.Blueprint;
import dev.friendai.features.construction.blueprint.PartKind;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;

/**
 * The structure and site {@code preview_structure} showed the owner, kept so {@code build_structure
 * use_preview=true} builds exactly that: the same resolved blueprint, origin, rotation, clearing rule, gathering
 * rule and selected parts. It is conversational state, held in memory beside the outline; nothing is persisted.
 */
public record PreviewIntent(
        Blueprint blueprint,
        BlockPos origin,
        Rotation rotation,
        boolean clearSite,
        boolean gather,
        Set<PartKind> parts,
        String dimension) {
    public PreviewIntent {
        parts = parts == null ? Set.of() : Set.copyOf(parts);
        dimension = dimension == null ? "" : dimension;
    }
}
