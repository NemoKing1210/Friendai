package dev.friendai.foundation.diagnostics;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** UTC names for the log tree: a day directory, a stream file and its rotation and digest siblings. */
public final class LogNames {
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HHmmss").withZone(ZoneOffset.UTC);

    private LogNames() {}

    /** Six lower-case hex characters that distinguish one boot from the next. */
    public static String bootId() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    public static String dayDir(long epochMillis) {
        return DAY.format(Instant.ofEpochMilli(epochMillis));
    }

    public static String file(LogFileKind kind, UUID owner, String boot, long startedMillis) {
        String base = switch (kind) {
            case BOOT -> "boot-" + boot;
            case SESSION -> "session-" + TIME.format(Instant.ofEpochMilli(startedMillis)) + "-" + boot;
            case CLIENT -> "client-" + boot;
        };
        return base + ".jsonl";
    }

    /** The first part keeps the plain name; later parts gain a numeric suffix before the extension. */
    public static String part(String file, int index) {
        if (index <= 1) return file;
        int dot = file.lastIndexOf('.');
        String base = dot < 0 ? file : file.substring(0, dot);
        String extension = dot < 0 ? "" : file.substring(dot);
        return base + "-" + index + extension;
    }

    public static String digestName(String jsonlFileName) {
        int dot = jsonlFileName.lastIndexOf('.');
        return dot < 0 ? jsonlFileName + ".md" : jsonlFileName.substring(0, dot) + ".md";
    }
}
