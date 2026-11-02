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

    /**
     * Whether the owner is online, or {@code null} when a pure caller cannot know. A guard must not treat unknown as
     * offline; only an explicit false satisfies {@code owner_offline}.
     */
    default Boolean ownerOnline() {
        return null;
    }

    /** Whether it is daytime, or {@code null} when the clock cannot be read. */
    default Boolean daytime() {
        return null;
    }

    /** {@code clear}, {@code rain} or {@code thunder}; empty when the weather cannot be read. */
    default String weather() {
        return "";
    }

    /** Experience levels, or a negative value when the caller cannot know them. */
    default int xpLevel() {
        return -1;
    }

    /** Whether a usable station of this kind is within the radius, or {@code null} when unknown. */
    default Boolean stationWithin(String station, int radius) {
        return null;
    }

    /** Whether a carried tool satisfies the vanilla harvest requirement for this block, or {@code null} when unknown. */
    default Boolean canHarvest(String block) {
        return null;
    }

    /** Whether the plan reached a terminal successful status, or {@code null} when the record is unavailable. */
    default Boolean planDone(long planId) {
        return null;
    }

    /** Whether the goal is recorded as met, or {@code null} when the record is unavailable. */
    default Boolean goalMet(long goalId) {
        return null;
    }
}
