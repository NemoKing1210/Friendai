package dev.friendai.features.memory.transcript;

/**
 * Hard bounds for one owner's transcript file. Entries are pruned by age and count when they are written and
 * again when they are read, so a lowered config never leaves an oversized file unreadable.
 */
public record TranscriptLimits(int maxEntries, int maxTextChars, long retentionMillis, long maxFileBytes) {
    public static final TranscriptLimits DEFAULTS =
            new TranscriptLimits(200, 400, 7L * 24 * 60 * 60 * 1000, 2L * 1024 * 1024);

    public TranscriptLimits {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        if (maxTextChars < 1) {
            throw new IllegalArgumentException("maxTextChars must be positive");
        }
        if (retentionMillis < 0) {
            throw new IllegalArgumentException("retention must not be negative");
        }
        if (maxFileBytes < 1) {
            throw new IllegalArgumentException("maxFileBytes must be positive");
        }
    }

    /** Builds the bounds from the config values; the file cap stays generous so a lowered cap cannot empty a file. */
    public static TranscriptLimits fromConfig(int maxEntries, int maxTextChars, int retentionDays) {
        int entries = Math.max(1, maxEntries);
        int chars = Math.max(1, maxTextChars);
        long derived = (long) entries * (chars + 160);
        long bytes = Math.min(8L * 1024 * 1024, Math.max(2L * 1024 * 1024, derived));
        return new TranscriptLimits(entries, chars, Math.max(0, retentionDays) * 24L * 60 * 60 * 1000, bytes);
    }
}
