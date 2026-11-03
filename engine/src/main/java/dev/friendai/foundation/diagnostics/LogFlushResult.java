package dev.friendai.foundation.diagnostics;

import java.nio.file.Path;

/**
 * The answer to a flush barrier: whether everything admitted before the barrier reached the disk, and how much of
 * the admitted work was processed and actually written.
 *
 * <p>{@code acceptedThrough} and {@code processedThrough} are admission-ticket ordinals, not record sequences: a
 * queued record that policy excluded is still processed, but it is not written. {@code dropped} counts records that
 * never got a ticket before the barrier was taken. A status other than {@link Status#FLUSHED} never claims a durable
 * flush.
 */
public record LogFlushResult(
        Status status,
        Path path,
        long acceptedThrough,
        long processedThrough,
        long written,
        long dropped,
        String errorCode) {

    public enum Status {
        /** Every admitted record was processed and the files were flushed. */
        FLUSHED,
        /** The barrier could not be admitted; nothing is promised about the offered records. */
        BUSY,
        /** The writer is disabled by a file error; accepted records were not written. */
        FAILED,
        /** The writer is closed; the barrier was not accepted. */
        CLOSED,
        /** File output is off; the ring still works, but there is no durable flush. */
        DISABLED
    }

    public LogFlushResult {
        path = path == null ? Path.of("") : path;
        errorCode = errorCode == null ? "" : errorCode;
    }

    /** True only for a barrier that completed with the files flushed. */
    public boolean durable() {
        return status == Status.FLUSHED;
    }

    public static LogFlushResult busy(String errorCode, Path path, long acceptedThrough, long dropped) {
        return new LogFlushResult(Status.BUSY, path, acceptedThrough, 0L, 0L, dropped, errorCode);
    }
}
