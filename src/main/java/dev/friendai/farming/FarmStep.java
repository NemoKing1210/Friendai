package dev.friendai.farming;

/** One thing the executor does, in plan order. */
public sealed interface FarmStep {
    /** Breaks (or picks) the ripe block at the cell. */
    record Harvest(FieldCell cell) implements FarmStep {}

    /** Plants {@code seed} where the cell was just harvested. */
    record Replant(FieldCell cell, String seed) implements FarmStep {}

    /** Plants {@code seed} on the empty soil of the cell. */
    record Sow(FieldCell cell, String seed) implements FarmStep {}

    /** Tills the ground of the cell into farmland. */
    record Till(FieldCell cell) implements FarmStep {}

    /** Uses bone meal on the growing crop of the cell until it is ripe or the meal runs out. */
    record Bonemeal(FieldCell cell) implements FarmStep {}

    /** The pass cannot finish the job without {@code count} more of {@code item}. */
    record NeedSeeds(String item, int count) implements FarmStep {}
}
