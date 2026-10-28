package dev.friendai.features.creative;

/** Current plain facts supplied by the Minecraft adapter; none of these facts is cached as authority. */
public record CreativePermissions(boolean owner, boolean online, boolean activationAllowed) {}
