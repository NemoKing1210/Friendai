package dev.friendai.foundation.diagnostics;

/**
 * What a scan and a render could not show: missing parents, truncated or unsupported records, dropped records, an
 * exhausted scan budget, and executions that never published a terminal. A report that hides any of these would claim
 * more certainty than the files contain.
 */
public record Completeness(
        int records,
        int missingParents,
        int truncatedRecords,
        int unsupportedRecords,
        long dropped,
        int openExecutions,
        boolean scanLimited) {
    public static final Completeness NONE = new Completeness(0, 0, 0, 0, 0L, 0, false);

    public Completeness {
        records = Math.max(0, records);
        missingParents = Math.max(0, missingParents);
        truncatedRecords = Math.max(0, truncatedRecords);
        unsupportedRecords = Math.max(0, unsupportedRecords);
        dropped = Math.max(0L, dropped);
        openExecutions = Math.max(0, openExecutions);
    }

    /** True only when nothing is known to be missing. */
    public boolean complete() {
        return missingParents == 0
                && truncatedRecords == 0
                && unsupportedRecords == 0
                && dropped == 0L
                && openExecutions == 0
                && !scanLimited;
    }

    public Completeness withRecords(int count) {
        return new Completeness(
                count, missingParents, truncatedRecords, unsupportedRecords, dropped, openExecutions, scanLimited);
    }

    public Completeness withOpenExecutions(int open) {
        return new Completeness(
                records, missingParents, truncatedRecords, unsupportedRecords, dropped, open, scanLimited);
    }
}
