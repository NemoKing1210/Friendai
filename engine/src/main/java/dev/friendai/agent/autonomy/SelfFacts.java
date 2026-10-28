package dev.friendai.agent.autonomy;

/**
 * Facts about the companion's own body and what its base offers, as opposed to the bag and the world.
 *
 * @param hunger the companion's own food level, 0..20
 * @param gearWear 0..1, the lowest remaining durability among carried tools and worn armor; 1 when nothing is worn
 * @param bedAtHome a bed inside the home place, so a sleep reflex has somewhere to go
 * @param enchantingTableAtHome an enchanting table inside the home place
 * @param anvilAtHome an anvil inside the home place
 */
public record SelfFacts(
        int hunger,
        int xpLevel,
        double gearWear,
        boolean bedAtHome,
        boolean enchantingTableAtHome,
        boolean anvilAtHome,
        boolean flintAndSteel,
        int obsidian) {

    public static final SelfFacts NONE = new SelfFacts(20, 0, 1.0, false, false, false, false, 0);

    public SelfFacts {
        hunger = Math.clamp(hunger, 0, 20);
        xpLevel = Math.max(0, xpLevel);
        gearWear = Double.isFinite(gearWear) ? Math.clamp(gearWear, 0.0, 1.0) : 1.0;
        obsidian = Math.max(0, obsidian);
    }

    /** A tool or armor piece is close to breaking. */
    public boolean gearWorn() {
        return gearWear < 0.15;
    }

    /** The companion is hungry enough to eat, or hungry with nothing carried. */
    public boolean hungry(int carriedFoodPoints) {
        return hunger <= 6 || (hunger <= 10 && carriedFoodPoints <= 0);
    }
}
