package dev.friendai.features.storage;

/** One matched record in a query, with its age and freshness verdict. */
public record StoredCandidate(ContainerRecord record, int count, long ageTicks, boolean stale) {
    public StoredCandidate {
        if (record == null) {
            throw new IllegalArgumentException("record is required");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
        if (ageTicks < 0) {
            throw new IllegalArgumentException("ageTicks must be non-negative");
        }
    }
}
