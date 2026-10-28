package dev.friendai.place;

import dev.friendai.features.navigation.pathing.Pos;

/**
 * A place a task noticed on its own, before the registry decides whether it is new. Produced by tasks and turned
 * into a stored place by {@link PlaceAutoRegistration}; nothing here touches a world or a clock.
 */
public record PlaceCandidate(
        PlaceKind kind,
        String label,
        String preferredName,
        String dimension,
        Pos anchor,
        int radius,
        PlaceSource source) {

    public PlaceCandidate {
        if (kind == null) {
            throw new IllegalArgumentException("A place candidate needs a kind.");
        }
        label = label == null ? "" : label.strip();
        preferredName = preferredName == null ? "" : preferredName.strip();
        if (dimension == null || dimension.isBlank()) {
            throw new IllegalArgumentException("A place candidate needs a dimension.");
        }
        dimension = dimension.strip();
        if (anchor == null) {
            throw new IllegalArgumentException("A place candidate needs an anchor position.");
        }
        if (radius < 1 || radius > Place.RADIUS_MAX) {
            throw new IllegalArgumentException(
                    "A place radius must be 1-" + Place.RADIUS_MAX + ", not " + radius + ".");
        }
        if (source == null) {
            throw new IllegalArgumentException("A place candidate needs a source.");
        }
    }
}
