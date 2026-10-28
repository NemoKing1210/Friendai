package dev.friendai.features.navigation.pathing;

/** Every cost the search uses, in ticks. A record so tests and configuration can override single values. */
public record CostModel(
        double walk,
        double diagonal,
        double jump,
        double fall,
        double swim,
        double climb,
        double door,
        double breakExtra,
        double place,
        double lavaNear,
        double waterNearBreak,
        double fallingExtra,
        int logPenalty) {

    /** Vanilla-like defaults tuned against the GameTests. */
    public static final CostModel DEFAULTS = new CostModel(4.6, 4.6 * 1.41, 2, 1, 9, 6, 4, 2, 5, 40, 20, 5, 20);
}
