package dev.friendai.features.places;

import dev.friendai.foundation.places.Place;
import dev.friendai.foundation.places.PlaceKind;
import dev.friendai.foundation.places.PlaceSource;
import dev.friendai.foundation.spatial.Pos;

/**
 * A place a task noticed on its own, before the registry decides whether it is new. Produced by tasks and turned
 * into a stored place by {@link PlaceAutoRegistration}; nothing here touches a world or a clock. The vertical band
 * defaults to a building's {@link Place#defaultBelow(int)} / {@link Place#defaultAbove(int)}; a dug site sets it to
 * the box it actually dug.
 */
public record PlaceCandidate(
        PlaceKind kind,
        String label,
        String preferredName,
        String dimension,
        Pos anchor,
        int radius,
        PlaceSource source,
        int below,
        int above) {

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
        if (below < 0 || below > Place.RADIUS_MAX || above < 0 || above > Place.RADIUS_MAX) {
            throw new IllegalArgumentException(
                    "A place band must be 0-" + Place.RADIUS_MAX + ", not " + below + "/" + above + ".");
        }
    }

    /** A candidate that keeps the default building band for its radius. */
    public PlaceCandidate(
            PlaceKind kind,
            String label,
            String preferredName,
            String dimension,
            Pos anchor,
            int radius,
            PlaceSource source) {
        this(
                kind,
                label,
                preferredName,
                dimension,
                anchor,
                radius,
                source,
                Place.defaultBelow(radius),
                Place.defaultAbove(radius));
    }
}
