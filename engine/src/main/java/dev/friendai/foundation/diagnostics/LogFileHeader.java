package dev.friendai.foundation.diagnostics;

import java.util.List;
import java.util.UUID;

/** The first line of every log file: enough to explain the file without the README. */
public record LogFileHeader(
        int schema,
        String boot,
        String mod,
        String minecraft,
        UUID owner,
        String name,
        String level,
        List<String> categories,
        long started) {
    public LogFileHeader {
        boot = boot == null ? "" : boot;
        mod = mod == null ? "" : mod;
        minecraft = minecraft == null ? "" : minecraft;
        name = name == null ? "" : name;
        level = level == null ? "" : level;
        categories = categories == null ? List.of() : List.copyOf(categories);
    }
}
