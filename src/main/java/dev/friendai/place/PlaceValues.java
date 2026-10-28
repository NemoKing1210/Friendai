package dev.friendai.place;

import dev.friendai.features.navigation.pathing.Pos;
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
        String note,
        int below,
        int above) {

    /** Values with the default vertical band for the radius; a caller that cares sets it explicitly. */
    public PlaceValues(
            String name,
            PlaceKind kind,
            String label,
            String dimension,
            Pos anchor,
            int radius,
            Set<String> aliases,
            PlaceSource source,
            UUID author,
            String note) {
        this(
                name,
                kind,
                label,
                dimension,
                anchor,
                radius,
                aliases,
                source,
                author,
                note,
                Place.defaultBelow(radius),
                Place.defaultAbove(radius));
    }
}
