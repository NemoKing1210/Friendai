package dev.friendai.place;

import dev.friendai.pathing.Pos;
import java.util.Set;
import java.util.UUID;

/** What a caller wants a place to be. The registry merges it into the stored record. */
public record PlaceValues(
        String name,
        PlaceKind kind,
        String label,
        String dimension,
        Pos anchor,
        int radius,
        Set<String> aliases,
        PlaceSource source,
        UUID author,
        String note) {}
