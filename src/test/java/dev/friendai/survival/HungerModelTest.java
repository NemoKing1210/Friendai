package dev.friendai.survival;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HungerModelTest {
    private static final float EPS = 0.0001f;

    private static HungerModel model(int food, float saturation, float exhaustion) {
        HungerModel model = new HungerModel();
        model.load(food, saturation, exhaustion);
        return model;
    }

    private static void tick(HungerModel model, int times, Difficulty difficulty,
            boolean naturalRegeneration, float health, float maxHealth) {
        for (int i = 0; i < times; i++) {
            model.tick(difficulty, naturalRegeneration, health, maxHealth);
        }
    }

    @Test
    void newModelStartsFull() {
        HungerModel model = new HungerModel();
        assertEquals(HungerModel.MAX_FOOD, model.food());
        assertEquals(5.0f, model.saturation(), EPS);
        assertEquals(0.0f, model.exhaustion(), EPS);
    }

    @Test
    void exhaustionDrainsSaturationFirst() {
        HungerModel model = model(20, 5.0f, 4.5f);

        model.tick(Difficulty.NORMAL, false, 20.0f, 20.0f);

        assertEquals(20, model.food());
        assertEquals(4.0f, model.saturation(), EPS);
        assertEquals(0.5f, model.exhaustion(), EPS);
    }

    @Test
    void exhaustionThenFood() {
        HungerModel model = model(20, 0.0f, 5.0f);

        model.tick(Difficulty.NORMAL, false, 20.0f, 20.0f);

        assertEquals(19, model.food());
        assertEquals(0.0f, model.saturation(), EPS);
        assertEquals(1.0f, model.exhaustion(), EPS);
    }

    @Test
    void peacefulNoFoodLoss() {
        HungerModel model = model(20, 0.0f, 5.0f);

        model.tick(Difficulty.PEACEFUL, false, 20.0f, 20.0f);

        assertEquals(20, model.food());
        assertEquals(0.0f, model.saturation(), EPS);
        assertEquals(1.0f, model.exhaustion(), EPS);
    }

    @Test
    void eatAddsFoodAndSaturation() {
        HungerModel model = model(10, 0.0f, 0.0f);

        // Bread: 5 nutrition, 0.6 saturation modifier.
        model.eat(5, 0.6f);

        assertEquals(15, model.food());
        assertEquals(6.0f, model.saturation(), EPS);
    }

    @Test
    void eatCapsSaturationAtFood() {
        HungerModel model = model(18, 18.0f, 0.0f);

        model.eat(5, 0.6f);

        assertEquals(20, model.food());
        assertEquals(20.0f, model.saturation(), EPS);
    }

    @Test
    void saturationRegenEvery10Ticks() {
        HungerModel model = model(20, 6.0f, 0.0f);

        for (int i = 0; i < 9; i++) {
            assertEquals(0.0f, model.tick(Difficulty.NORMAL, true, 10.0f, 20.0f).heal(), EPS);
        }

        HungerModel.TickResult result = model.tick(Difficulty.NORMAL, true, 10.0f, 20.0f);

        assertEquals(1.0f, result.heal(), EPS);
        assertEquals(0.0f, result.damage(), EPS);
        assertEquals(6.0f, model.saturation(), EPS);
        assertEquals(6.0f, model.exhaustion(), EPS);
    }

    @Test
    void slowRegenEvery80Ticks() {
        HungerModel model = model(18, 0.0f, 0.0f);

        for (int i = 0; i < 79; i++) {
            assertEquals(0.0f, model.tick(Difficulty.NORMAL, true, 10.0f, 20.0f).heal(), EPS);
        }

        HungerModel.TickResult result = model.tick(Difficulty.NORMAL, true, 10.0f, 20.0f);

        assertEquals(1.0f, result.heal(), EPS);
        assertEquals(6.0f, model.exhaustion(), EPS);
    }

    @Test
    void sharedTimerCarriesAcrossRegenBranches() {
        HungerModel model = model(20, 0.0f, 0.0f);

        // 79 slow-regen ticks: the shared timer sits at 79, nothing healed yet.
        tick(model, 79, Difficulty.NORMAL, true, 15.0f, 20.0f);

        // Saturation regen becomes possible, food stays 20, saturation becomes 2.
        model.eat(1, 1.0f);
        assertEquals(2.0f, model.saturation(), EPS);

        HungerModel.TickResult result = model.tick(Difficulty.NORMAL, true, 15.0f, 20.0f);

        // Vanilla's shared timer hits 80 and fires in the saturation branch.
        assertEquals(2.0f / 6.0f, result.heal(), EPS);
        assertEquals(2.0f, model.exhaustion(), EPS);
    }

    @Test
    void noRegenWhenGameruleOff() {
        HungerModel model = model(20, 6.0f, 0.0f);

        tick(model, 100, Difficulty.NORMAL, false, 10.0f, 20.0f);

        assertEquals(6.0f, model.saturation(), EPS);
        assertEquals(0.0f, model.exhaustion(), EPS);
    }

    @Test
    void easyStarvesTo10() {
        HungerModel hurt = model(0, 0.0f, 0.0f);
        for (int i = 0; i < 79; i++) {
            assertEquals(0.0f, hurt.tick(Difficulty.EASY, false, 11.0f, 20.0f).damage(), EPS);
        }
        assertEquals(1.0f, hurt.tick(Difficulty.EASY, false, 11.0f, 20.0f).damage(), EPS);

        HungerModel safe = model(0, 0.0f, 0.0f);
        for (int i = 0; i < 80; i++) {
            assertEquals(0.0f, safe.tick(Difficulty.EASY, false, 10.0f, 20.0f).damage(), EPS);
        }
    }

    @Test
    void normalStopsAtOneHealth() {
        HungerModel hurt = model(0, 0.0f, 0.0f);
        tick(hurt, 79, Difficulty.NORMAL, false, 2.0f, 20.0f);
        assertEquals(1.0f, hurt.tick(Difficulty.NORMAL, false, 2.0f, 20.0f).damage(), EPS);

        HungerModel safe = model(0, 0.0f, 0.0f);
        for (int i = 0; i < 80; i++) {
            assertEquals(0.0f, safe.tick(Difficulty.NORMAL, false, 1.0f, 20.0f).damage(), EPS);
        }
    }

    @Test
    void hardStarvesToDeath() {
        HungerModel model = model(0, 0.0f, 0.0f);

        tick(model, 79, Difficulty.HARD, false, 1.0f, 20.0f);
        assertEquals(1.0f, model.tick(Difficulty.HARD, false, 1.0f, 20.0f).damage(), EPS);
        tick(model, 79, Difficulty.HARD, false, 1.0f, 20.0f);
        assertEquals(1.0f, model.tick(Difficulty.HARD, false, 1.0f, 20.0f).damage(), EPS);
    }

    @Test
    void peacefulRefillsFood() {
        HungerModel model = model(10, 0.0f, 0.0f);

        tick(model, 19, Difficulty.PEACEFUL, false, 20.0f, 20.0f);
        assertEquals(10, model.food());

        model.tick(Difficulty.PEACEFUL, false, 20.0f, 20.0f);
        assertEquals(11, model.food());

        HungerModel full = model(20, 0.0f, 0.0f);
        tick(full, 40, Difficulty.PEACEFUL, false, 20.0f, 20.0f);
        assertEquals(20, full.food());
    }

    @Test
    void exhaustionCappedAt40() {
        HungerModel model = new HungerModel();

        model.addExhaustion(39.0f);
        model.addExhaustion(10.0f);

        assertEquals(40.0f, model.exhaustion(), EPS);
    }

    @Test
    void loadRestoresValuesAndClamps() {
        HungerModel model = new HungerModel();

        model.load(37, 100.0f, 99.0f);

        assertEquals(20, model.food());
        assertEquals(20.0f, model.saturation(), EPS);
        assertEquals(40.0f, model.exhaustion(), EPS);

        model.load(-5, 3.0f, -1.0f);

        assertEquals(0, model.food());
        assertEquals(0.0f, model.saturation(), EPS);
        assertEquals(0.0f, model.exhaustion(), EPS);
    }
}
