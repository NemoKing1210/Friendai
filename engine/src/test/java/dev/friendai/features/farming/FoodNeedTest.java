package dev.friendai.features.farming;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FoodNeedTest {
    @Test
    void carryingFoodMeansNoNeed() {
        assertEquals(FoodNeed.NONE, FoodNeed.of(20, true));
        assertEquals(FoodNeed.NONE, FoodNeed.of(3, true));
    }

    @Test
    void noFoodAndFullStomachIsLow() {
        assertEquals(FoodNeed.LOW, FoodNeed.of(20, false));
        assertEquals(FoodNeed.LOW, FoodNeed.of(15, false));
    }

    @Test
    void noFoodAndHungryIsHigh() {
        assertEquals(FoodNeed.HIGH, FoodNeed.of(14, false));
        assertEquals(FoodNeed.HIGH, FoodNeed.of(2, false));
    }
}
