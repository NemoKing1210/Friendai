package dev.friendai.features.farming;

/** How badly the companion itself needs food; steers which crop is sown. */
public enum FoodNeed {
    NONE,
    LOW,
    HIGH;

    private static final int HUNGRY_FOOD_LEVEL = 14;

    /** HIGH when hungry without food, LOW when merely without food, NONE when it carries some. */
    public static FoodNeed of(int foodLevel, boolean carriesFood) {
        if (carriesFood) {
            return NONE;
        }
        return foodLevel <= HUNGRY_FOOD_LEVEL ? HIGH : LOW;
    }
}
