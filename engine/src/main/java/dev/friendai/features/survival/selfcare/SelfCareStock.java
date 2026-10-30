package dev.friendai.features.survival.selfcare;

/** What the companion carries for an emergency, counted across the whole inventory. */
public record SelfCareStock(
        int waterBuckets,
        int emptyBuckets,
        int milkBuckets,
        int goldenApples,
        int enchantedGoldenApples,
        int healingPotions,
        int regenerationPotions,
        int fireResistancePotions,
        int waterBreathingPotions,
        int slowFallingPotions,
        int splashHealingPotions) {

    public static final SelfCareStock EMPTY = new SelfCareStock(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    public int count(SelfCareItem item) {
        return switch (item) {
            case WATER_BUCKET -> waterBuckets;
            case EMPTY_BUCKET -> emptyBuckets;
            case MILK_BUCKET -> milkBuckets;
            case GOLDEN_APPLE -> goldenApples;
            case ENCHANTED_GOLDEN_APPLE -> enchantedGoldenApples;
            case POTION_HEALING -> healingPotions;
            case POTION_REGENERATION -> regenerationPotions;
            case POTION_FIRE_RESISTANCE -> fireResistancePotions;
            case POTION_WATER_BREATHING -> waterBreathingPotions;
            case POTION_SLOW_FALLING -> slowFallingPotions;
            case SPLASH_POTION_HEALING -> splashHealingPotions;
        };
    }

    /**
     * "1 water bucket, milk, 2 golden apples" for the prompt: one entry per kind carried, the empty bucket left out
     * because it is a tool for taking water back rather than something to spend, or "" when nothing is carried.
     */
    public String toPromptText() {
        StringBuilder text = new StringBuilder();
        for (SelfCareItem item : SelfCareItem.values()) {
            if (item == SelfCareItem.EMPTY_BUCKET) {
                continue;
            }
            int count = count(item);
            if (count <= 0) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append(", ");
            }
            if (count > 1) {
                text.append(count).append(' ');
            }
            text.append(item.display(count));
        }
        return text.toString();
    }

    /** True when nothing at all is carried, so a caller can skip rendering. */
    public boolean isEmpty() {
        return waterBuckets == 0
                && emptyBuckets == 0
                && milkBuckets == 0
                && goldenApples == 0
                && enchantedGoldenApples == 0
                && healingPotions == 0
                && regenerationPotions == 0
                && fireResistancePotions == 0
                && waterBreathingPotions == 0
                && slowFallingPotions == 0
                && splashHealingPotions == 0;
    }
}
