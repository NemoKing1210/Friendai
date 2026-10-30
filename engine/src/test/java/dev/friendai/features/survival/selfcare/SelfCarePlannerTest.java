package dev.friendai.features.survival.selfcare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SelfCarePlannerTest {
    private static final String POISON = "minecraft:poison";
    private static final String SPEED = "minecraft:speed";
    private static final String SLOWNESS = "minecraft:slowness";
    private static final String FIRE_RESISTANCE = "minecraft:fire_resistance";
    private static final String WATER_BREATHING = "minecraft:water_breathing";
    private static final String SLOW_FALLING = "minecraft:slow_falling";

    @Test
    void fallingWithABucketPoursWater() {
        SelfCareDecision decision = decide(facts().falling(9.0f, 1.5, true)
                .stock(stock(1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0))
                .build());
        assertEquals(SelfCareKind.WATER_DROP, decision.kind());
        assertEquals(SelfCareItem.WATER_BUCKET, decision.item());
    }

    @Test
    void fallingWithoutAReachableFaceAbstains() {
        assertTrue(maybe(facts().falling(9.0f, -1.0, false)
                        .stock(stock(1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0))
                        .build())
                .isEmpty());
        assertTrue(maybe(facts().falling(9.0f, 1.5, false)
                        .stock(stock(1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0))
                        .build())
                .isEmpty());
        assertTrue(
                maybe(facts().falling(9.0f, 3.0, true)
                                .stock(stock(1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0))
                                .build())
                        .isEmpty(),
                "a face past the bucket's reach must not be aimed at");
    }

    @Test
    void fallingWithNoBucketDrinksSlowFalling() {
        SelfCareDecision decision = decide(facts().falling(9.0f, 1.5, true)
                .stock(stock(0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0))
                .build());
        assertEquals(SelfCareKind.SLOW_FALLING, decision.kind());
        assertTrue(maybe(facts().falling(9.0f, 1.5, true)
                        .effect(SLOW_FALLING, 200, EffectMark.BENEFICIAL)
                        .stock(stock(0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0))
                        .build())
                .isEmpty());
    }

    @Test
    void aShallowDropIsNotAnEmergency() {
        assertTrue(maybe(facts().falling(2.0f, 1.5, true)
                        .stock(stock(1, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0))
                        .build())
                .isEmpty());
    }

    @Test
    void lavaIsAnsweredWithFireResistanceFirst() {
        SelfCareDecision decision = decide(
                facts().lava().stock(stock(0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0)).build());
        assertEquals(SelfCareKind.FIRE_RESISTANCE, decision.kind());
        assertTrue(maybe(facts().lava()
                        .effect(FIRE_RESISTANCE, 400, EffectMark.BENEFICIAL)
                        .stock(stock(0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0))
                        .build())
                .isEmpty());
    }

    @Test
    void criticalHealthPrefersThePlainAppleThenTheEnchantedOne() {
        SelfCareStock both = stock(0, 0, 0, 2, 1, 1, 1, 0, 0, 0, 0);
        assertEquals(
                SelfCareKind.GOLDEN_APPLE,
                decide(facts().health(0.25f).stock(both).build()).kind());
        assertEquals(
                SelfCareKind.ENCHANTED_GOLDEN_APPLE,
                decide(facts().health(0.10f).stock(both).build()).kind());
        assertEquals(
                SelfCareKind.ENCHANTED_GOLDEN_APPLE,
                decide(facts().health(0.25f)
                                .stock(stock(0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                                .build())
                        .kind(),
                "the plain apple is gone, so the enchanted one is spent");
        assertEquals(
                SelfCareKind.HEALING_POTION,
                decide(facts().health(0.25f)
                                .stock(stock(0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0))
                                .build())
                        .kind());
        assertEquals(
                SelfCareKind.REGENERATION_POTION,
                decide(facts().health(0.25f)
                                .stock(stock(0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0))
                                .build())
                        .kind());
    }

    @Test
    void healthyCompanionNeverSpendsAnApple() {
        assertTrue(maybe(facts().health(0.8f)
                        .stock(stock(0, 0, 0, 3, 3, 1, 1, 0, 0, 0, 0))
                        .build())
                .isEmpty());
    }

    @Test
    void milkClearsAHarmfulEffectWithTimeLeft() {
        SelfCareDecision decision = decide(facts().effect(POISON, 400, EffectMark.HARMFUL)
                .stock(stock(0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0))
                .build());
        assertEquals(SelfCareKind.MILK, decision.kind());
        assertEquals(SelfCareItem.MILK_BUCKET, decision.item());
    }

    @Test
    void milkAbstainsWhenNothingIsWorthClearing() {
        SelfCareStock milk = stock(0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0);
        assertTrue(
                maybe(facts().effect(SPEED, 400, EffectMark.BENEFICIAL)
                                .stock(milk)
                                .build())
                        .isEmpty(),
                "only beneficial effects are up");
        assertTrue(
                maybe(facts().effect(POISON, 40, EffectMark.HARMFUL).stock(milk).build())
                        .isEmpty(),
                "the poison is nearly over");
        assertTrue(
                maybe(facts().effect(SLOWNESS, 400, EffectMark.HARMFUL)
                                .stock(milk)
                                .build())
                        .isEmpty(),
                "slowness without a job to slow costs nothing");
        assertEquals(
                SelfCareKind.MILK,
                decide(facts().working(true)
                                .effect(SLOWNESS, 400, EffectMark.HARMFUL)
                                .stock(milk)
                                .build())
                        .kind(),
                "slowness that costs the current job is worth clearing");
    }

    @Test
    void milkAbstainsWhileABeneficialEffectCarriesTheSituation() {
        SelfCareStock milk = stock(0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0);
        assertTrue(
                maybe(facts().lava()
                                .effect(FIRE_RESISTANCE, 400, EffectMark.BENEFICIAL)
                                .effect(POISON, 400, EffectMark.HARMFUL)
                                .stock(milk)
                                .build())
                        .isEmpty(),
                "fire resistance is what keeps it alive in lava");
        assertTrue(maybe(facts().submerged(true)
                        .effect(WATER_BREATHING, 400, EffectMark.BENEFICIAL)
                        .effect(POISON, 400, EffectMark.HARMFUL)
                        .stock(milk)
                        .build())
                .isEmpty());
    }

    @Test
    void runningOutOfAirDrinksWaterBreathing() {
        SelfCareDecision decision = decide(facts().submerged(true)
                .air(100)
                .stock(stock(0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0))
                .build());
        assertEquals(SelfCareKind.WATER_BREATHING, decision.kind());
        assertTrue(
                maybe(facts().submerged(true)
                                .air(280)
                                .stock(stock(0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0))
                                .build())
                        .isEmpty(),
                "full lungs need no potion");
    }

    @Test
    void aWoundedOwnerGetsASplashPotionOnlyWhenItIsSafe() {
        SelfCareStock splash = stock(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1);
        SelfCareDecision decision =
                decide(facts().owner(2.5, 0.4f, false).stock(splash).build());
        assertEquals(SelfCareKind.SPLASH_HEAL_OWNER, decision.kind());
        assertTrue(
                maybe(facts().owner(2.5, 0.4f, true).stock(splash).build()).isEmpty(),
                "a hostile next to the owner would be caught in it");
        assertTrue(maybe(facts().owner(2.5, 1.0f, false).stock(splash).build()).isEmpty());
        assertTrue(maybe(facts().owner(20.0, 0.2f, false).stock(splash).build()).isEmpty());
    }

    @Test
    void anEmptyInventoryAlwaysAbstains() {
        assertTrue(maybe(facts().falling(20.0f, 1.0, true).build()).isEmpty());
        assertTrue(maybe(facts().lava().health(0.05f).submerged(true).air(0).build())
                .isEmpty());
    }

    @Test
    void oneItemPerEmergency() {
        Optional<SelfCareDecision> decision = maybe(facts().health(0.1f)
                .effect(POISON, 400, EffectMark.HARMFUL)
                .owner(2.0, 0.2f, false)
                .stock(stock(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1))
                .build());
        assertTrue(decision.isPresent());
        assertEquals(SelfCareKind.ENCHANTED_GOLDEN_APPLE, decision.orElseThrow().kind());
    }

    @Test
    void aBurningCompanionLeavesItToTheFireReflex() {
        assertTrue(maybe(facts().fire()
                        .health(0.05f)
                        .stock(stock(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1))
                        .build())
                .isEmpty());
    }

    @Test
    void swimmingInWaterIsNotAFall() {
        assertTrue(maybe(facts().falling(9.0f, 1.0, true)
                        .inWater(true)
                        .stock(stock(1, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0))
                        .build())
                .isEmpty());
    }

    private static SelfCareDecision decide(SelfCareFacts facts) {
        return SelfCarePlanner.decide(facts).orElseThrow();
    }

    private static Optional<SelfCareDecision> maybe(SelfCareFacts facts) {
        return SelfCarePlanner.decide(facts);
    }

    private static SelfCareStock stock(
            int water,
            int empty,
            int milk,
            int golden,
            int enchanted,
            int healing,
            int regeneration,
            int fire,
            int breath,
            int slow,
            int splash) {
        return new SelfCareStock(
                water, empty, milk, golden, enchanted, healing, regeneration, fire, breath, slow, splash);
    }

    private static Facts facts() {
        return new Facts();
    }

    /** Mutable test builder; only the fields a case cares about are set. */
    private static final class Facts {
        private float health = 1.0f;
        private boolean onGround = true;
        private boolean inWater;
        private boolean submerged;
        private boolean inLava;
        private boolean onFire;
        private float fallDistance;
        private double supportDistance = -1.0;
        private boolean supportPlaceable;
        private int air = 300;
        private boolean working;
        private boolean ownerPresent;
        private double ownerDistance = 100.0;
        private float ownerHealth = 1.0f;
        private boolean ownerThreatNear;
        private final java.util.List<SelfCareEffect> effects = new java.util.ArrayList<>();
        private SelfCareStock stock = SelfCareStock.EMPTY;

        Facts health(float fraction) {
            this.health = fraction;
            return this;
        }

        Facts falling(float distance, double support, boolean placeable) {
            this.onGround = false;
            this.fallDistance = distance;
            this.supportDistance = support;
            this.supportPlaceable = placeable;
            return this;
        }

        Facts inWater(boolean value) {
            this.inWater = value;
            return this;
        }

        Facts submerged(boolean value) {
            this.submerged = value;
            this.inWater = value;
            return this;
        }

        Facts lava() {
            this.inLava = true;
            this.onGround = false;
            return this;
        }

        Facts fire() {
            this.onFire = true;
            return this;
        }

        Facts air(int ticks) {
            this.air = ticks;
            return this;
        }

        Facts working(boolean value) {
            this.working = value;
            return this;
        }

        Facts owner(double distance, float health, boolean threatNear) {
            this.ownerPresent = true;
            this.ownerDistance = distance;
            this.ownerHealth = health;
            this.ownerThreatNear = threatNear;
            return this;
        }

        Facts effect(String id, int ticks, EffectMark mark) {
            this.effects.add(new SelfCareEffect(id, 0, ticks, mark));
            return this;
        }

        Facts stock(SelfCareStock value) {
            this.stock = value;
            return this;
        }

        SelfCareFacts build() {
            return new SelfCareFacts(
                    health,
                    onGround,
                    inWater,
                    submerged,
                    inLava,
                    onFire,
                    fallDistance,
                    supportDistance,
                    supportPlaceable,
                    air,
                    working,
                    ownerPresent,
                    ownerDistance,
                    ownerHealth,
                    ownerThreatNear,
                    List.copyOf(effects),
                    stock);
        }
    }
}
