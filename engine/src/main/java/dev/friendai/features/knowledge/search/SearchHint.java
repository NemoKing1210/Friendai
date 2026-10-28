package dev.friendai.features.knowledge.search;

import dev.friendai.features.navigation.pathing.Pos;
import java.util.Objects;

/**
 * A model hint, not a waypoint. It may name a direction, a landmark, a biome or a rough area, with a one-line
 * reason, and the engine decides whether to honour it: the hint only adds a bounded bonus to matching frontier
 * scores and expires by tick. A bad hint therefore costs one leg, not a lost expedition.
 */
public record SearchHint(
        SearchPlanner.Heading direction,
        String landmark,
        String biome,
        Pos area,
        int areaRadius,
        String reason,
        long createdTick,
        long expiresTick) {
    public static final double BONUS = 6.0;
    public static final long TTL_TICKS = 2_400;
    public static final int REASON_MAX = 160;
    public static final int LANDMARK_MAX = 64;

    public SearchHint {
        if (createdTick < 0 || expiresTick <= createdTick) throw new IllegalArgumentException("invalid hint window");
        if (direction == null && isBlank(landmark) && isBlank(biome) && area == null)
            throw new IllegalArgumentException("a hint needs a direction, landmark, biome or area");
        landmark = clean(landmark, LANDMARK_MAX, "landmark");
        biome = biome == null ? null : clean(biome, LANDMARK_MAX, "biome");
        reason = clean(reason, REASON_MAX, "reason");
        if (area != null && (areaRadius < 1 || areaRadius > 2_048))
            throw new IllegalArgumentException("areaRadius must be between 1 and 2048");
        if (biome != null && biome.contains(" ")) throw new IllegalArgumentException("biome must be one id or word");
    }

    public static SearchHint create(
            SearchPlanner.Heading direction,
            String landmark,
            String biome,
            Pos area,
            int areaRadius,
            String reason,
            long now) {
        return new SearchHint(direction, landmark, biome, area, areaRadius, reason, now, now + TTL_TICKS);
    }

    public boolean expired(long now) {
        return now >= expiresTick;
    }

    /** The bonus this hint adds to a frontier, or zero when it does not match or has expired. */
    public double bonusFor(SearchPlanner.Frontier frontier, Pos origin, long now) {
        if (expired(now) || frontier == null || !matches(frontier, origin)) return 0;
        return BONUS;
    }

    public boolean matches(SearchPlanner.Frontier frontier, Pos origin) {
        Objects.requireNonNull(frontier, "frontier");
        if (direction != null && headingMatches(frontier.approach(), origin)) return true;
        if (biome != null && biome.equals(frontier.terrain().biomeId())) return true;
        if (area != null && distance(frontier.approach(), area) <= areaRadius) return true;
        return false;
    }

    /** A one-line description for prompt text and task history; never a coordinate request. */
    public String describe(long now) {
        StringBuilder text = new StringBuilder();
        if (direction != null) text.append("direction ").append(direction.name().toLowerCase(java.util.Locale.ROOT));
        if (!isBlank(landmark)) append(text, "landmark " + landmark);
        if (!isBlank(biome)) append(text, "biome " + biome);
        if (area != null) append(text, "area " + area.x() + " " + area.z() + " radius " + areaRadius);
        append(text, isBlank(reason) ? "no reason given" : reason);
        text.append(expired(now) ? " (expired)" : " (expires at tick " + expiresTick + ")");
        return text.toString();
    }

    /** World checks the adapter performs once, before a hint is stored. */
    public record ValidationFacts(
            boolean destinationLoaded,
            boolean destinationLand,
            boolean destinationAllowed,
            boolean destinationInWorld) {}

    public record Validation(boolean accepted, String reason) {
        public static Validation ok() {
            return new Validation(true, "");
        }

        public static Validation reject(String reason) {
            return new Validation(false, reason);
        }
    }

    public Validation validate(ValidationFacts facts, long now) {
        Objects.requireNonNull(facts, "facts");
        if (expired(now)) return Validation.reject("the hint has expired");
        if (!facts.destinationInWorld()) return Validation.reject("the hint points outside the world border");
        if (!facts.destinationLoaded()) return Validation.reject("the hint destination is not loaded");
        if (!facts.destinationLand()) return Validation.reject("the hint destination is water or lava");
        if (!facts.destinationAllowed()) return Validation.reject("the hint destination is inside a protected place");
        return Validation.ok();
    }

    private boolean headingMatches(Pos approach, Pos origin) {
        int dx = approach.x() - origin.x(), dz = approach.z() - origin.z();
        if (dx == 0 && dz == 0) return false;
        SearchPlanner.Heading actual = Math.abs(dx) > Math.abs(dz)
                ? (dx > 0 ? SearchPlanner.Heading.EAST : SearchPlanner.Heading.WEST)
                : (dz < 0 ? SearchPlanner.Heading.NORTH : SearchPlanner.Heading.SOUTH);
        return actual == direction;
    }

    private static String clean(String text, int max, String what) {
        if (text == null) return null;
        String value = text.replaceAll("\\p{Cntrl}", " ").strip().replaceAll("\\s+", " ");
        if (value.length() > max) throw new IllegalArgumentException(what + " is longer than " + max + " characters");
        return value.isEmpty() ? null : value;
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private static void append(StringBuilder text, String part) {
        if (!text.isEmpty()) text.append("; ");
        text.append(part);
    }

    private static double distance(Pos a, Pos b) {
        double dx = (double) a.x() - b.x(), dz = (double) a.z() - b.z();
        return Math.hypot(dx, dz);
    }
}
