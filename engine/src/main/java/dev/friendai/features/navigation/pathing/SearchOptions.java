package dev.friendai.features.navigation.pathing;

/**
 * What one search may do: how many nodes it may expand, how far from the start it may look, how many blocks the
 * companion may place and what the cheapest block it carries is worth.
 *
 * @param placeable how many blocks may be placed; 0 disables placing, {@link Integer#MAX_VALUE} is the material probe
 * @param placePrice the material price of the cheapest block the companion carries (1 when probing)
 * @param runningJumps whether the body can sprint into a jump. Without it only the narrow gaps a walking jump clears
 *     are leapt, and the search walks around wider ones
 */
public record SearchOptions(int maxNodes, int maxRange, int placeable, double placePrice, boolean runningJumps) {

    /** The defaults the server config falls back to. */
    public static final SearchOptions DEFAULTS = new SearchOptions(20_000, 64, 0, 1);

    /** A search for a caller that has no body to ask about food: every move stays available. */
    public SearchOptions(int maxNodes, int maxRange, int placeable, double placePrice) {
        this(maxNodes, maxRange, placeable, placePrice, true);
    }
}
