package dev.friendai.debug;

import java.nio.file.Path;

/** What the writer has done so far: offered, accepted, dropped, flushed and failed. */
public record LogWriterStats(long accepted, long dropped, long written, long errors, Path currentFile) {}
