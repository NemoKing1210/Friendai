package dev.friendai.place;

import dev.friendai.pathing.Pos;
import java.util.Objects;

/**
 * Decides what a task's {@link PlaceCandidate} means for one owner's book: refresh a place the task passed again,
 * remember a new one, or skip when its name space is used up.
 *
 * <p>A decision is pure: {@code decide} reads the registry and writes nothing. The caller applies the outcome
 * through {@link PlaceRegistry#remember}, which lets it stamp the owner as the author and keeps
 * {@link PlaceRegistry} the only mutator. That is also why two consecutive calls see the same registry until the
 * first outcome has been applied.
 */
public final class PlaceAutoRegistration {

    private PlaceAutoRegistration() {}

    /** What the caller should record about the candidate. */
    public enum Decision {
        CREATE,
        UPDATE,
        SKIP
    }

    /**
     * The decision. {@code targetId} is the id of the place to refresh for {@link Decision#UPDATE}, and {@code 0}
     * for {@link Decision#CREATE} (the place is not stored yet) and {@link Decision#SKIP}; {@code name} is the
     * name to store, or empty for a skip.
     */
    public record Outcome(Decision decision, long targetId, String name) {
        public Outcome {
            Objects.requireNonNull(decision, "decision");
            name = name == null ? "" : name;
        }
    }

    /**
     * Finds the place the candidate should refresh (same kind, normalized label and dimension, anchor within
     * {@code mergeRadius} blocks by Chebyshev distance including height), or chooses the name a new place should
     * get: {@code preferredName} when it is free, else {@link PlaceNamer}. Returns {@link Decision#SKIP} only when
     * no automatic name is left. Writes nothing; the caller applies the outcome with {@link PlaceRegistry#remember}
     * and the tick it chooses. {@code tick} stays in the signature for the planned call site.
     */
    public static Outcome decide(
            PlaceRegistry registry, PlaceCandidate candidate, int mergeRadius, long tick) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(candidate, "candidate");

        String label = foldLabel(candidate.label());
        Place mergeTarget = null;
        long nearest = Long.MAX_VALUE;
        for (Place place : registry.all()) {
            if (place.kind() != candidate.kind() || !place.dimension().equals(candidate.dimension())) {
                continue;
            }
            if (!foldLabel(place.label()).equals(label)) {
                continue;
            }
            long distance = chebyshev(place.anchor(), candidate.anchor());
            if (distance > mergeRadius) {
                continue;
            }
            if (mergeTarget == null || distance < nearest
                    || (distance == nearest && place.id() < mergeTarget.id())) {
                mergeTarget = place;
                nearest = distance;
            }
        }
        if (mergeTarget != null) {
            return new Outcome(Decision.UPDATE, mergeTarget.id(), mergeTarget.name());
        }

        String name = freePreferredName(registry, candidate);
        if (name == null) {
            name = automaticName(registry, candidate);
        }
        if (name == null) {
            return new Outcome(Decision.SKIP, 0, "");
        }
        return new Outcome(Decision.CREATE, 0, name);
    }

    private static String freePreferredName(PlaceRegistry registry, PlaceCandidate candidate) {
        String preferred = candidate.preferredName();
        if (preferred.isBlank() || preferred.length() > PlaceLimits.NAME_MAX) {
            return null;
        }
        try {
            PlaceNames.normalize(preferred);
        } catch (IllegalArgumentException notAName) {
            return null;
        }
        return registry.find(preferred, candidate.dimension()).isEmpty() ? preferred : null;
    }

    private static String automaticName(PlaceRegistry registry, PlaceCandidate candidate) {
        try {
            return PlaceNamer.autoName(
                    registry, candidate.dimension(), candidate.kind(), candidate.label());
        } catch (IllegalArgumentException exhausted) {
            return null;
        }
    }

    private static long chebyshev(Pos first, Pos second) {
        return Math.max(
                Math.abs((long) first.x() - second.x()),
                Math.max(
                        Math.abs((long) first.y() - second.y()),
                        Math.abs((long) first.z() - second.z())));
    }

    private static String foldLabel(String label) {
        if (label == null || label.isBlank()) {
            return "";
        }
        try {
            return PlaceNames.normalize(label);
        } catch (IllegalArgumentException notALabel) {
            return "";
        }
    }
}
