package dev.friendai.features.memory;

/** Size limits applied when a fact is written. */
public record MemoryLimits(int maxFactChars, int maxFactsPerPlayer, int maxWorldFacts) {
    public static final MemoryLimits DEFAULTS = new MemoryLimits(512, 128, 128);
}
