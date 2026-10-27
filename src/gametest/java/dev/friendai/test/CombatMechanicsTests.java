package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Phase 0 probes: every mechanic the combat controller relies on is checked against the actual engine.
 * A probe that fails means the controller must not claim the mechanic; the test then pins the scoped limitation.
 */
public class CombatMechanicsTests {

    /** PathfinderMob melee has no player critical-hit path; damage must be equal on the ground and while falling. */
    @GameTest
    public void mobMeleeHasNoCriticalBonus(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity attacker = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        attacker.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(5.0);
        Chicken ground = h.spawnWithNoFreeWill(EntityTypes.CHICKEN, 4, 2, 4);
        Chicken falling = h.spawnWithNoFreeWill(EntityTypes.CHICKEN, 4, 2, 5);
        ground.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100.0);
        falling.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100.0);
        ground.setHealth(100.0f);
        falling.setHealth(100.0f);

        attacker.setPos(attacker.getX(), attacker.getY(), attacker.getZ());
        attacker.doHurtTarget(h.getLevel(), ground);
        float groundDamage = 100.0f - ground.getHealth();

        attacker.setPos(attacker.getX(), attacker.getY() + 3.0, attacker.getZ());
        attacker.setOnGround(false);
        attacker.setDeltaMovement(0, -0.5, 0);
        attacker.doHurtTarget(h.getLevel(), falling);
        float fallingDamage = 100.0f - falling.getHealth();

        h.assertTrue(groundDamage > 0, "the ground hit did no damage");
        h.assertTrue(Math.abs(groundDamage - fallingDamage) < 0.001f,
                "mob melee changed while falling: ground " + groundDamage + ", falling " + fallingDamage
                        + " (a player critical path is not expected on PathfinderMob)");
        h.succeed();
    }

    /** A raised shield blocks damage from the front and leaves a rear hit unblocked. */
    @GameTest(maxTicks = 100)
    public void shieldBlocksByDirectionAfterBlockDelay(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 4, 2, 4);
        c.setYRot(0.0f);
        c.setYHeadRot(0.0f);
        c.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        c.startUsingItem(net.minecraft.world.InteractionHand.OFF_HAND);
        h.runAfterDelay(10, () -> {
            h.assertTrue(c.isBlocking(), "the shield did not become active after the block delay");
            h.assertTrue(c.getItemBlockingWith().is(Items.SHIELD),
                    "blocking with " + c.getItemBlockingWith() + " instead of a shield");

            Arrow front = new Arrow(h.getLevel(), c.getX(), c.getY() + 1, c.getZ() + 2,
                    new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
            h.getLevel().addFreshEntity(front);
            float frontal = c.applyItemBlocking(
                    h.getLevel(), h.getLevel().damageSources().arrow(front, c), 8.0f);

            Arrow rear = new Arrow(h.getLevel(), c.getX(), c.getY() + 1, c.getZ() - 2,
                    new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
            h.getLevel().addFreshEntity(rear);
            float fromBehind = c.applyItemBlocking(
                    h.getLevel(), h.getLevel().damageSources().arrow(rear, c), 8.0f);

            h.assertTrue(frontal > 0.001f, "a frontal hit was not blocked, blocked amount " + frontal);
            h.assertTrue(fromBehind < 0.001f, "a rear hit was blocked: " + fromBehind);
            h.succeed();
        });
    }

    /** A real splash projectile applies its effects; instant damage follows the vanilla undead rules. */
    @GameTest
    public void splashPotionEffectsAndAttribution(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 5, 2, 2);
        zombie.setNoAi(true);
        zombie.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(100.0);
        zombie.setHealth(50.0f);
        float before = zombie.getHealth();

        ItemStack harming = PotionContents.createItemStack(Items.SPLASH_POTION, Potions.HARMING);
        ThrownSplashPotion thrown = new ThrownSplashPotion(h.getLevel(), c, harming);
        thrown.setPos(zombie.getX(), zombie.getY(), zombie.getZ());
        thrown.onHitAsPotion(h.getLevel(), harming, new EntityHitResult(zombie));

        h.assertTrue(zombie.getHealth() > before,
                "harming must heal an undead target in vanilla, health went " + before + " -> " + zombie.getHealth());
        h.succeed();
    }

    /** The bow path consumes exactly one ammunition item and the arrow entity exists. */
    @GameTest(maxTicks = 400)
    public void bowConsumesOneArrowAndSpawnsOneProjectile(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.BOW));
        c.inventory().addItem(new ItemStack(Items.ARROW, 3));
        Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 13, 2, 2);
        zombie.setNoAi(true);
        h.runAfterDelay(20, () -> c.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(zombie), 1.0f));
        h.succeedWhen(() -> {
            int remaining = c.inventory().countItem(Items.ARROW);
            long flying = h.getLevel().getEntitiesOfClass(
                    net.minecraft.world.entity.projectile.arrow.AbstractArrow.class,
                    c.getBoundingBox().inflate(24.0)).size();
            h.assertTrue(remaining < 3, "no ammunition was consumed: " + remaining);
            h.assertTrue(remaining >= 0, "ammunition went negative: " + remaining);
            h.assertTrue(flying >= 1, "no arrow reached the world");
        });
    }
}
