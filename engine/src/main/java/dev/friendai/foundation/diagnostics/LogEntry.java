package dev.friendai.foundation.diagnostics;

public record LogEntry(long id, long timeMillis, LogKind kind, String text) {}
