package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.features.combat.minecraft.Threats;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;

/** The two threat queries: what may be attacked, and what must only be avoided. */
public class ThreatClassificationTests {
    @GameTest(maxTicks = 100)
    public void wardenIsDangerNotTarget(GameTestHelper h) {
        CompanionEntity companion = setup(h, 80);
        var warden = h.spawn(EntityTypes.WARDEN, 5, 81, 5);
        warden.setNoAi(true);
        var level = h.getLevel();
        h.assertTrue(
                Threats.threatsNear(level, companion.position(), 16, companion).stream()
                        .noneMatch(entity -> entity == warden),
                "the warden must never be a target");
        h.assertTrue(
                Threats.dangersNear(level, companion.position(), 16, companion).stream()
                        .anyMatch(entity -> entity == warden),
                "the warden must be a danger");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void aLonePiglinIsNeutralUntilProvoked(GameTestHelper h) {
        CompanionEntity companion = setup(h, 82);
        ZombifiedPiglin piglin = h.spawn(EntityTypes.ZOMBIFIED_PIGLIN, 5, 83, 5);
        piglin.setNoAi(true);
        var level = h.getLevel();
        h.assertTrue(
                Threats.threatsNear(level, companion.position(), 16, companion).stream()
                        .noneMatch(entity -> entity == piglin),
                "an unprovoked zombified piglin is not a target");
        h.assertTrue(
                Threats.dangersNear(level, companion.position(), 16, companion).stream()
                        .noneMatch(entity -> entity == piglin),
                "an unprovoked lone zombified piglin is not a danger");
        piglin.setTarget(companion);
        h.assertTrue(
                Threats.threatsNear(level, companion.position(), 16, companion).stream()
                        .anyMatch(entity -> entity == piglin),
                "a provoked zombified piglin is a target");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void aPiglinGroupIsAvoided(GameTestHelper h) {
        CompanionEntity companion = setup(h, 84);
        ZombifiedPiglin first = h.spawn(EntityTypes.ZOMBIFIED_PIGLIN, 4, 85, 4);
        ZombifiedPiglin second = h.spawn(EntityTypes.ZOMBIFIED_PIGLIN, 6, 85, 6);
        first.setNoAi(true);
        second.setNoAi(true);
        var level = h.getLevel();
        var dangers = Threats.dangersNear(level, companion.position(), 16, companion);
        h.assertTrue(dangers.contains(first) && dangers.contains(second), "both are dangers");
        h.assertTrue(
                Threats.threatsNear(level, companion.position(), 16, companion).stream()
                        .noneMatch(entity -> entity == first || entity == second),
                "a group is not attacked on the companion's initiative");
        h.assertTrue(Threats.assess(first, companion, true).attackable(), "the owner's order lifts the group rule");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void aZombieIsAnOrdinaryTarget(GameTestHelper h) {
        CompanionEntity companion = setup(h, 86);
        var zombie = h.spawn(EntityTypes.ZOMBIE, 5, 87, 5);
        zombie.setNoAi(true);
        h.assertTrue(
                Threats.threatsNear(h.getLevel(), companion.position(), 16, companion).stream()
                        .anyMatch(entity -> entity == zombie),
                "a zombie is hostile");
        h.succeed();
    }

    private static CompanionEntity setup(GameTestHelper h, int floor) {
        GameTestSupport.platformAt(h, floor, 16);
        var player = GameTestSupport.mockPlayerAt(h, 2, floor + 1, 2);
        return CompanionManager.get(h.getLevel().getServer()).summon(player);
    }
}
