package dev.friendai.features.construction.special;

import dev.friendai.features.construction.blueprint.Blueprint;
import java.util.List;

/**
 * A block layout plus the finishing action that triggers a vanilla mechanic.
 *
 * @param body the blocks built before the trigger; a {@link Trigger.LastBlock} block is not part of it
 * @param requiredItems item ids needed besides body blocks (the lighter for a portal)
 * @param allowedInEnd whether the mechanic can work in the End
 */
public record SpecialStructure(
        String id,
        Blueprint body,
        Trigger trigger,
        Outcome outcome,
        Footprint footprint,
        List<String> requiredItems,
        boolean allowedInEnd) {
    public SpecialStructure {
        requiredItems = List.copyOf(requiredItems);
    }
}
