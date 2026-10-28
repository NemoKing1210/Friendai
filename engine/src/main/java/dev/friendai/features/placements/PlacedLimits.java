package dev.friendai.features.placements;

/**
 * Hard bounds for one owner's placement ledger. The cap is a bound, not a preference: the prompt and the tool
 * limit what the model sees, and the oldest entry is evicted rather than refusing a placement.
 */
public record PlacedLimits(int maxEntries, long maxFileBytes) {

    public static final PlacedLimits DEFAULTS = new PlacedLimits(512, 1L << 20);

    public PlacedLimits {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        if (maxFileBytes <= 0) {
            throw new IllegalArgumentException("maxFileBytes must be positive");
        }
    }
}
