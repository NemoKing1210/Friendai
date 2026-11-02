package dev.friendai.execution.queue;

/** World facts needed by the pure queue guards. */
public interface GuardFacts {
    int countItems(String idOrTag);

    int emptySlots();

    float healthFraction();

    int ownerDistance();

    int countBlocks(String idOrTag, int radius);

    String dimension();

    /** Hunger points, or a negative value when the caller cannot know them. */
    default int foodLevel() {
        return -1;
    }

    default boolean blockInFront(String idOrTag, int distance) {
        return false;
    }

    default String biome() {
        return "";
    }

    default boolean fluidAhead() {
        return false;
    }

    default int distanceTravelled() {
        return 0;
    }

    default long timeElapsed() {
        return 0;
    }

    default boolean hasBlocks(String idOrTag, int radius, int count) {
        return countBlocks(idOrTag, radius) >= count;
    }

    /** Items of this kind in the owner's inventory, or 0 when the owner cannot be read. */
    default int ownerCountItems(String idOrTag) {
        return 0;
    }

    /** Whether the named block sits at exactly this position, or false when the position cannot be read. */
    default boolean blockAt(String idOrTag, int x, int y, int z) {
        return false;
    }
}
