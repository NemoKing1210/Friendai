package dev.friendai.knowledge;

public record KnowledgeLimits(int memberObservations, int sites, int coverageEntries, int trailNodes, int aliases) {
    public static final KnowledgeLimits DEFAULTS = new KnowledgeLimits(16_384, 2_048, 4_096, 1_024, 2_048);

    public KnowledgeLimits {
        if (memberObservations < 0 || sites < 0 || coverageEntries < 0 || trailNodes < 0 || aliases < 0) {
            throw new IllegalArgumentException("knowledge limits must not be negative");
        }
    }
}
