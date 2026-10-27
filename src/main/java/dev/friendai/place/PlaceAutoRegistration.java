package dev.friendai.place;

import dev.friendai.pathing.Pos;
import java.util.Objects;
import java.util.Set;

/**
 * Turns a task's {@link PlaceCandidate} into a change to one owner's book: refresh a place the task passed again,
 * remember a new one, or skip when its name space is used up.
 *
 * <p>The decision is applied to the registry in the same call, so a farm found on two consecutive passes ends up
 * as one place rather than two. {@code tick} stamps the created or refreshed place. The call is pure Java: the
 * caller supplies the tick, and nothing reads a world or a clock.
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
     * The applied outcome. {@code targetId} is the new or refreshed place id, or {@code 0} for {@link
     * Decision#SKIP}; {@code name} is the stored name, or empty for a skip.
     */
    public record Outcome(Decision decision, long targetId, String name) {
        public Outcome {
            Objects.requireNonNull(decision, "decision");
            name = name == null ? "" : name;
        }
    }

    /**
     * Merges the candidate into a place of the same kind and normalized label in the same dimension whose anchor
     * is within {@code mergeRadius} blocks (Chebyshev, including height); otherwise remembers a new place named by
     * {@code preferredName} when that is free, else by {@link PlaceNamer}. Returns {@link Decision#SKIP} only when
     * no automatic name is left.
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
            Place updated = registry.remember(values(mergeTarget.name(), candidate), tick);
            return new Outcome(Decision.UPDATE, updated.id(), updated.name());
        }

        String name = freePreferredName(registry, candidate);
        if (name == null) {
            name = automaticName(registry, candidate);
        }
        if (name == null) {
            return new Outcome(Decision.SKIP, 0, "");
        }
        Place created = registry.remember(values(name, candidate), tick);
        return new Outcome(Decision.CREATE, created.id(), created.name());
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

    private static PlaceValues values(String name, PlaceCandidate candidate) {
        return new PlaceValues(
                name, candidate.kind(), candidate.label(), candidate.dimension(), candidate.anchor(),
                candidate.radius(), Set.of(), candidate.source(), null, "");
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
