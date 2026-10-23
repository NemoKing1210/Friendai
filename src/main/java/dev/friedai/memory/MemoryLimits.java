package dev.friedai.memory;

/** Size limits applied when a fact is written. */
public record MemoryLimits(int maxFactChars, int maxFactsPerPlayer, int maxWorldFacts) {
    public static final MemoryLimits DEFAULTS = new MemoryLimits(160, 20, 30);
}
