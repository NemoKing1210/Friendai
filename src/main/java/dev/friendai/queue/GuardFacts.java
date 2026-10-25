package dev.friendai.queue;

/** World facts needed by the pure queue guards. */
public interface GuardFacts {
    int countItems(String idOrTag);
    int emptySlots();
    float healthFraction();
    int ownerDistance();
    int countBlocks(String idOrTag, int radius);
    String dimension();

    default boolean hasBlocks(String idOrTag, int radius, int count) {
        return countBlocks(idOrTag, radius) >= count;
    }
}
