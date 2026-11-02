package dev.friendai.foundation.diagnostics;

import java.nio.file.Path;
import java.util.UUID;

/** What retention needs to know about one file on disk. */
public record LogFileInfo(Path path, long bytes, long modifiedMillis, UUID owner, LogFileKind kind) {}
