package dev.friendai.farming;

/** What a cell of the field is, as far as planning goes. */
public enum CellKind {
    /** A mature block that can be harvested. */
    RIPE,
    /** A crop that is still growing. */
    GROWING,
    /** Soil with nothing planted on it. */
    EMPTY_SOIL,
    /** Dirt-like ground that could be tilled into farmland. */
    TILLABLE,
    /** Anything the planner must leave alone. */
    BLOCKED
}
