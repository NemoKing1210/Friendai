package dev.friendai.tool;

import java.util.UUID;

/** Who the current turn acts for. Names are labels only; rights are decided by {@link #access()}. */
public record Caller(UUID uuid, String name, AccessLevel access) {}
