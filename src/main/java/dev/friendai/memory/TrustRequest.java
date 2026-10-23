package dev.friendai.memory;

import java.util.UUID;

/** A trust change the owner asked for and has not confirmed yet. */
public record TrustRequest(
        int id, UUID owner, UUID target, String targetName, boolean trust, long expiresAtMillis) {}
