package dev.friendai.crafting.obtain;

/**
 * A tool's material, ordered from weakest to strongest so {@link #compareTo} means "at least as good".
 * {@link #NONE} is the tier of a bare hand or of any tool when only the kind matters.
 */
public enum Tier {
    NONE,
    WOOD,
    STONE,
    IRON,
    DIAMOND,
    NETHERITE
}
