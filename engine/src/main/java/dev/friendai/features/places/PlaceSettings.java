package dev.friendai.features.places;

/** Immutable place settings passed from server configuration to Minecraft adapters. */
public record PlaceSettings(
        boolean enabled, boolean autoRemember, int maxPlaces, int mergeRadius, int promptLimit, int promptBudget) {}
