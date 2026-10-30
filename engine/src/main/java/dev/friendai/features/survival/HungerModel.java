package dev.friendai.features.survival;

/**
 * A companion's food, saturation and exhaustion, ported from vanilla
 * {@code FoodData}: the same thresholds, timers and heal/damage amounts.
 *
 * <p>Pure Java: the caller ticks the model, then applies the returned
 * {@link TickResult} to the entity. The model knows nothing about the world.
 */
public final class HungerModel {
    public static final int MAX_FOOD = 20;

    /** Vanilla {@code FoodData.hasEnoughFood}: sprinting needs more than six food points. */
    private static final int SPRINT_FOOD_LEVEL = 6;

    private static final float MAX_EXHAUSTION = 40.0f;
    private static final float STARTING_SATURATION = 5.0f;
    private static final int SATURATION_REGEN_INTERVAL = 10;
    private static final int SLOW_REGEN_INTERVAL = 80;
    private static final int STARVE_INTERVAL = 80;
    private static final int PEACEFUL_REGEN_INTERVAL = 20;

    private int food;
    private float saturation;
    private float exhaustion;

    private int tickTimer;
    private int peacefulTimer;

    /** A full companion: food 20, saturation 5, exhaustion 0. */
    public HungerModel() {
        this.food = MAX_FOOD;
        this.saturation = STARTING_SATURATION;
    }

    public int food() {
        return food;
    }

    public float saturation() {
        return saturation;
    }

    public float exhaustion() {
        return exhaustion;
    }

    /** Vanilla {@code FoodData.hasEnoughFood()}: running needs food above six points. */
    public boolean hasEnoughFood() {
        return food > SPRINT_FOOD_LEVEL;
    }

    /** Adds exhaustion, capped at 40 like vanilla. */
    public void addExhaustion(float amount) {
        exhaustion = Math.min(exhaustion + amount, MAX_EXHAUSTION);
    }

    /**
     * Eats food with the given nutrition and saturation modifier: food grows by
     * {@code nutrition} (at most 20), saturation by {@code nutrition * modifier * 2}
     * (never above the new food level).
     */
    public void eat(int nutrition, float saturationModifier) {
        food = clamp(food + nutrition, 0, MAX_FOOD);
        saturation = clamp(saturation + nutrition * saturationModifier * 2.0f, 0.0f, food);
    }

    /**
     * Advances the model by one tick and returns what the entity must do now.
     * The caller applies the heal and damage; the model only computes them.
     */
    public TickResult tick(Difficulty difficulty, boolean naturalRegeneration, float health, float maxHealth) {
        float heal = 0.0f;
        float damage = 0.0f;

        if (exhaustion > 4.0f) {
            exhaustion -= 4.0f;
            if (saturation > 0.0f) {
                saturation = clamp(saturation - 1.0f, 0.0f, food);
            } else if (difficulty != Difficulty.PEACEFUL) {
                food = Math.max(food - 1, 0);
            }
        }

        if (difficulty == Difficulty.PEACEFUL) {
            peacefulTimer++;
            if (peacefulTimer >= PEACEFUL_REGEN_INTERVAL) {
                peacefulTimer = 0;
                if (food < MAX_FOOD) {
                    food++;
                }
            }
        } else {
            peacefulTimer = 0;
        }

        // Vanilla shares one tickTimer across the saturation-regen, slow-regen
        // and starvation branches, so a switch carries partial progress; only
        // the final else resets it.
        boolean hurt = health < maxHealth;
        if (naturalRegeneration && saturation > 0.0f && food >= MAX_FOOD && hurt) {
            tickTimer++;
            if (tickTimer >= SATURATION_REGEN_INTERVAL) {
                float f = Math.min(saturation, 6.0f);
                heal = f / 6.0f;
                addExhaustion(f);
                tickTimer = 0;
            }
        } else if (naturalRegeneration && food >= 18 && hurt) {
            tickTimer++;
            if (tickTimer >= SLOW_REGEN_INTERVAL) {
                heal = 1.0f;
                addExhaustion(6.0f);
                tickTimer = 0;
            }
        } else if (food <= 0) {
            tickTimer++;
            if (tickTimer >= STARVE_INTERVAL) {
                tickTimer = 0;
                damage = starveDamage(difficulty, health);
            }
        } else {
            tickTimer = 0;
        }

        return new TickResult(heal, damage);
    }

    /** Restores saved values, clamped to the valid ranges; timers start over. */
    public void load(int food, float saturation, float exhaustion) {
        this.food = clamp(food, 0, MAX_FOOD);
        this.saturation = clamp(saturation, 0.0f, this.food);
        this.exhaustion = clamp(exhaustion, 0.0f, MAX_EXHAUSTION);
        tickTimer = 0;
        peacefulTimer = 0;
    }

    private static float starveDamage(Difficulty difficulty, float health) {
        // Spec deviation: vanilla 26.3 also starves on PEACEFUL when health > 10.
        boolean hurt = switch (difficulty) {
            case PEACEFUL -> false;
            case EASY -> health > 10.0f;
            case NORMAL -> health > 1.0f;
            case HARD -> true;
        };
        return hurt ? 1.0f : 0.0f;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(value, max));
    }

    /** What the entity must apply after a tick: healing and starvation damage. */
    public record TickResult(float heal, float damage) {}
}
