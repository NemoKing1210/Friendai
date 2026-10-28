package dev.friendai.queue;

import java.util.Map;

record FakeGuardFacts(
        Map<String, Integer> items,
        int slots,
        float health,
        int ownerDistance,
        Map<String, Integer> blocks,
        String dimension)
        implements GuardFacts {
    @Override
    public int countItems(String idOrTag) {
        return items.getOrDefault(idOrTag, 0);
    }

    @Override
    public int emptySlots() {
        return slots;
    }

    @Override
    public float healthFraction() {
        return health;
    }

    @Override
    public int ownerDistance() {
        return ownerDistance;
    }

    @Override
    public int countBlocks(String idOrTag, int radius) {
        return blocks.getOrDefault(idOrTag, 0);
    }

    @Override
    public String dimension() {
        return dimension;
    }
}
