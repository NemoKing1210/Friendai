package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.phys.EntityHitResult;

/** Splash potion effects through the real projectile, and the controller's consumption rules. */
public class CombatPotionTests {

    private static void splash(GameTestHelper h, CompanionEntity c, Zombie target, net.minecraft.core.Holder<net.minecraft.world.item.alchemy.Potion> potion) {
        ItemStack stack = PotionContents.createItemStack(Items.SPLASH_POTION, potion);
        ThrownSplashPotion thrown = new ThrownSplashPotion(h.getLevel(), c, stack);
        thrown.setPos(target.getX(), target.getY(), target.getZ());
        thrown.onHitAsPotion(h.getLevel(), stack, new EntityHitResult(target));
    }

    @GameTest
    public void splashProjectileAppliesExpectedEffects(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 5, 2, 2);
        zombie.setNoAi(true);
        zombie.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(100.0);
        zombie.setHealth(50.0f);
        float before = zombie.getHealth();
        splash(h, c, zombie, Potions.HARMING);
        h.assertTrue(zombie.getHealth() > before,
                "harming must heal an undead target; health " + before + " -> " + zombie.getHealth());
        h.succeed();
    }

    @GameTest
    public void undeadEffectSemanticsMatchVanilla(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Zombie healedByHarm = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 2, 2, 2);
        Zombie hurtByHealing = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 12, 2, 12);
        healedByHarm.setNoAi(true);
        hurtByHealing.setNoAi(true);
        healedByHarm.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(100.0);
        hurtByHealing.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(100.0);
        healedByHarm.setHealth(50.0f);
        hurtByHealing.setHealth(100.0f);

        splash(h, c, healedByHarm, Potions.HARMING);
        splash(h, c, hurtByHealing, Potions.HEALING);
        h.assertTrue(healedByHarm.getHealth() > 50.0f,
                "harming did not heal the undead: " + healedByHarm.getHealth());
        h.assertTrue(hurtByHealing.getHealth() < 100.0f,
                "healing did not hurt the undead: " + hurtByHealing.getHealth());
        h.succeed();
    }

    @GameTest(maxTicks = 200)
    public void blockedThrowConsumesNothing(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setCombatSettings(c.combatSettings().withSplashPotions(true));
        c.inventory().addItem(PotionContents.createItemStack(Items.SPLASH_POTION, Potions.HARMING));
        Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 22, 2, 2);
        zombie.setNoAi(true);
        h.runAfterDelay(10, () -> c.combatController().fight(zombie));
        h.runAfterDelay(40, () -> {
            h.assertValueEqual(c.inventory().countItem(Items.SPLASH_POTION), 1,
                    "an out-of-range potion was consumed");
            h.assertTrue(h.getLevel().getEntitiesOfClass(ThrownSplashPotion.class,
                            c.getBoundingBox().inflate(24.0)).isEmpty(),
                    "a potion was thrown out of range");
            zombie.kill(h.getLevel());
            c.combatController().stopFight();
            h.succeed();
        });
    }

    @GameTest(maxTicks = 300)
    public void validThrowConsumesExactlyOnce(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setCombatSettings(c.combatSettings().withSplashPotions(true));
        c.inventory().addItem(PotionContents.createItemStack(Items.SPLASH_POTION, Potions.HARMING));
        Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 8, 2, 2);
        zombie.setNoAi(true);
        zombie.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(100.0);
        zombie.setHealth(100.0f);
        h.runAfterDelay(10, () -> c.combatController().fight(zombie));
        h.succeedWhen(() -> {
            int left = c.inventory().countItem(Items.SPLASH_POTION);
            h.assertTrue(left == 0, "waiting for the potion throw");
            h.assertValueEqual(left, 0, "the potion count went negative or duplicated");
            h.assertTrue(h.getLevel().getEntitiesOfClass(ThrownSplashPotion.class,
                            c.getBoundingBox().inflate(24.0)).size() <= 1,
                    "more than one potion was thrown from one stack");
        });
    }

    @GameTest(maxTicks = 200)
    public void lastPotionNeverHitsFriendlies(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 5, 2, 2);
        zombie.setNoAi(true);
        var owner = GameTestSupport.mockPlayerAt(h, 4, 2, 2);
        c.setOwner(owner.getUUID());
        c.setCombatSettings(c.combatSettings().withSplashPotions(true));
        c.inventory().addItem(PotionContents.createItemStack(Items.SPLASH_POTION, Potions.HARMING));
        h.assertValueEqual(c.inventory().countItem(Items.SPLASH_POTION), 1,
                "the test potion was not in the inventory before the fight");
        float ownerHealth = owner.getHealth();
        h.assertTrue(dev.friendai.task.combat.Threats.isProtected(owner, c),
                "the mock owner must count as a protected entity");
        h.assertTrue(owner.distanceTo(zombie) <= 4.0,
                "the owner must be inside the impact radius, distance " + owner.distanceTo(zombie));
        h.runAfterDelay(10, () -> c.combatController().fight(zombie));
        h.runAfterDelay(80, () -> {
            h.assertValueEqual(owner.getHealth(), ownerHealth, "the owner took splash damage");
            h.assertValueEqual(c.inventory().countItem(Items.SPLASH_POTION), 1,
                    "a friendly-splash potion was consumed");
            h.assertTrue(h.getLevel().getEntitiesOfClass(ThrownSplashPotion.class,
                            c.getBoundingBox().inflate(24.0)).isEmpty(),
                    "a potion was thrown despite the owner inside the impact area");
            zombie.kill(h.getLevel());
            c.combatController().stopFight();
            h.succeed();
        });
    }
}
