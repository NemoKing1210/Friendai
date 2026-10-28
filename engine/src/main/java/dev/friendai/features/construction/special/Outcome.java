package dev.friendai.features.construction.special;

/** What counts as success once the trigger has fired. */
public sealed interface Outcome {
    /** An entity of {@code entityType} exists within {@code radius} blocks of the structure. */
    record EntitySpawned(String entityType, int radius) implements Outcome {}

    /** At least {@code minCount} blocks of {@code state} exist inside the footprint. */
    record BlocksPresent(String state, int minCount) implements Outcome {}
}
