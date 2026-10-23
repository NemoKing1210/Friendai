package dev.friendai.debug;

public record LogEntry(long id, long timeMillis, LogKind kind, String text) {}
