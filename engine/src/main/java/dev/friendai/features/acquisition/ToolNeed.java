package dev.friendai.features.acquisition;

import dev.friendai.foundation.item.Tier;
import java.util.Objects;

/**
 * A tool requirement, e.g. {@code (PICKAXE, STONE)}. A {@code HAND}/{@code NONE} need is satisfied by
 * anything, and a {@code NONE} tier accepts any tier of the same kind.
 */
public record ToolNeed(ToolKind kind, Tier tier) {
    public ToolNeed {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(tier, "tier");
    }

    /** A bare-hand requirement. */
    public static final ToolNeed HAND = new ToolNeed(ToolKind.HAND, Tier.NONE);

    /** True when a carried tool of this kind and tier or better satisfies the need. */
    public boolean satisfiedBy(ToolNeed carried) {
        if (carried == null) {
            return false;
        }
        if (kind == ToolKind.HAND || tier == Tier.NONE) {
            return true;
        }
        return carried.kind == kind && carried.tier.compareTo(tier) >= 0;
    }

    /** The vanilla item id for this need, or null for a hand need. */
    public String vanillaItem() {
        if (kind == ToolKind.HAND) {
            return null;
        }
        if (kind == ToolKind.SHEARS) {
            return "minecraft:shears";
        }
        if (tier == Tier.NONE) {
            return null;
        }
        return "minecraft:" + tierWord() + "_" + kind.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** The words used in texts: {@code wooden pickaxe}. */
    public String words() {
        if (kind == ToolKind.HAND) {
            return "hand";
        }
        if (kind == ToolKind.SHEARS) {
            return "shears";
        }
        String tool = kind.name().toLowerCase(java.util.Locale.ROOT);
        return tier == Tier.NONE ? tool : tierWord() + " " + tool;
    }

    /** The material word used in item ids and texts; wood tools are "wooden". */
    private String tierWord() {
        return tier == Tier.WOOD ? "wooden" : tier.name().toLowerCase(java.util.Locale.ROOT);
    }
}
