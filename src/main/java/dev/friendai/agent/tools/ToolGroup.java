package dev.friendai.agent.tools;

import java.util.Locale;
import java.util.Optional;

/** The families of tools; they mirror the {@code *Tools} classes so a new tool lands in an obvious group. */
public enum ToolGroup {
    CORE,
    MOVEMENT,
    GATHERING,
    INVENTORY,
    BUILDING,
    STORAGE,
    CRAFTING,
    COOKING,
    ENCHANT,
    COMBAT,
    FARMING,
    ANIMALS,
    OBTAIN,
    TRADING,
    RESOURCE,
    BLUEPRINT,
    STRUCTURE,
    INFO,
    MEMORY,
    TRUST,
    CONTROL,
    BODY,
    INTERACTION,
    PLACE,
    AUTONOMY;

    /** The group named by the model, ignoring case; empty for anything unknown. */
    public static Optional<ToolGroup> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String normalized = text.strip().toUpperCase(Locale.ROOT);
        for (ToolGroup group : values()) {
            if (group.name().equals(normalized)) {
                return Optional.of(group);
            }
        }
        return Optional.empty();
    }
}
