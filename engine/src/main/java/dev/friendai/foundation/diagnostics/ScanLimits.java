package dev.friendai.foundation.diagnostics;

/** How much of a file one scan may touch. */
public record ScanLimits(int maxBytes, int maxRecords) {
    public static final ScanLimits DEFAULT = new ScanLimits(2 << 20, 5000);

    public ScanLimits {
        maxBytes = Math.max(1, maxBytes);
        maxRecords = Math.max(1, maxRecords);
    }
}
