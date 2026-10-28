package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.features.combat.ProtectionPolicy;
import dev.friendai.task.combat.CombatController;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Shield, evasion and cleanup behaviour of the server-side arbiter. */
public class CombatDefenseTests {

    /** A shooter's draw raises the shield before the arrow exists, so the released arrow does no damage. */
    @GameTest(maxTicks = 200)
    public void skeletonDrawRaisesShieldBeforeArrow(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 4, 2, 4);
        c.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        Skeleton skeleton = h.spawn(EntityTypes.SKELETON, 12, 2, 4);
        skeleton.setNoAi(true);
        skeleton.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        skeleton.setTarget(c);
        skeleton.startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
        c.combatController().fight(skeleton);
        h.runAfterDelay(10, () -> {
            h.assertTrue(skeleton.getUseItem().is(Items.BOW), "the skeleton is not drawing: " + skeleton.getUseItem());
            h.assertTrue(
                    h.getLevel()
                            .getEntitiesOfClass(
                                    net.minecraft.world.entity.projectile.arrow.AbstractArrow.class,
                                    c.getBoundingBox().inflate(24.0))
                            .isEmpty(),
                    "an arrow exists before the draw was detected");
            h.assertTrue(c.isBlocking(), "the shield was not raised during the draw: " + c.getItemBlockingWith());
        });
        h.runAfterDelay(20, () -> skeleton.performRangedAttack(c, 0.2F));
        h.runAfterDelay(80, () -> {
            h.assertTrue(
                    c.getHealth() == c.getMaxHealth(),
                    "the arrow from the draw was not blocked: " + c.getHealth() + "/" + c.getMaxHealth() + ", source "
                            + c.getLastDamageSource());
            c.combatController().stopFight();
            h.succeed();
        });
    }

    /** A bow draw yields the use slot when a shooter starts drawing: the shield is really up, not only claimed. */
    @GameTest(maxTicks = 200)
    public void shieldInterruptsABowDraw(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 4, 2, 4);
        c.inventory().addItem(new ItemStack(Items.BOW));
        c.inventory().addItem(new ItemStack(Items.ARROW, 8));
        c.inventory().addItem(new ItemStack(Items.STONE_SWORD));
        c.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        Zombie far = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 11, 2, 4);
        far.setNoAi(true);
        far.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100.0);
        far.setHealth(100.0f);
        Skeleton skeleton = h.spawn(EntityTypes.SKELETON, 4, 2, 12);
        skeleton.setNoAi(true);
        skeleton.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        skeleton.setTarget(c);
        h.runAfterDelay(
                5, () -> c.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(far), 1.0f));
        h.runAfterDelay(15, () -> {
            h.assertTrue(
                    c.combatController().state() == CombatController.State.FIGHTING,
                    "the companion is not fighting the far zombie: "
                            + c.combatController().state());
            skeleton.startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
        });
        h.runAfterDelay(25, () -> {
            h.assertTrue(
                    c.isBlocking(),
                    "the shield is claimed but not active: holding " + c.getUseItem()
                            + ", blocking " + c.isBlocking()
                            + ", action " + c.combatController().activeAction());
            h.assertTrue(c.getUseItem().is(Items.SHIELD), "the bow still holds the use slot: " + c.getUseItem());
            skeleton.kill(h.getLevel());
            far.kill(h.getLevel());
            c.combatController().stopFight();
            h.succeed();
        });
    }

    /** A displaced offhand stack comes back after the fight, and the shield is not duplicated. */
    @GameTest(maxTicks = 200)
    public void shieldRestoresOffhandOnStop(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 3, 2, 3);
        c.setCombatSettings(c.combatSettings().withStyle(dev.friendai.features.combat.FightingStyle.CAUTIOUS));
        c.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TORCH));
        c.inventory().addItem(new ItemStack(Items.SHIELD));
        Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 4, 2, 3);
        zombie.setNoAi(true);
        c.combatController().fight(zombie);
        h.runAfterDelay(40, () -> {
            h.assertTrue(
                    c.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD),
                    "cautious melee did not raise a shield: " + c.getItemBySlot(EquipmentSlot.OFFHAND) + ", state "
                            + c.combatController().state());
            c.combatController().stopFight();
        });
        h.runAfterDelay(42, () -> {
            h.assertTrue(
                    c.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.TORCH),
                    "the torch was not restored: " + c.getItemBySlot(EquipmentSlot.OFFHAND));
            int shields = c.inventory().countItem(Items.SHIELD);
            h.assertValueEqual(shields, 1, "shield count changed");
            h.assertTrue(
                    c.combatController().state() == CombatController.State.IDLE,
                    "state after stop: " + c.combatController().state());
            h.succeed();
        });
    }

    /** Switching to avoid-combat during a reflex fight releases the paused task and clears combat state. */
    @GameTest(maxTicks = 300)
    public void protectionChangeEndsReflexFightAndResumesTask(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 3, 2, 3);
        Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 5, 2, 3);
        zombie.setNoAi(true);
        zombie.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH)
                .setBaseValue(100.0);
        zombie.setHealth(100.0f);
        c.executionCoordinator().start(new dev.friendai.task.builtin.StayTask(), "stay");
        h.runAfterDelay(
                5, () -> c.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(zombie), 1.0f));
        h.runAfterDelay(30, () -> {
            h.assertTrue(
                    c.combatController().state() == CombatController.State.FIGHTING,
                    "the reflex fight did not start: " + c.combatController().state());
            h.assertTrue(c.executionCoordinator().paused(), "the runner was not paused by the reflex fight");
            c.setCombatSettings(c.combatSettings().withProtection(ProtectionPolicy.AVOID_COMBAT));
        });
        h.runAfterDelay(40, () -> zombie.kill(h.getLevel()));
        h.runAfterDelay(120, () -> {
            h.assertTrue(
                    c.combatController().state() == CombatController.State.IDLE,
                    "the old fight survived the settings change: "
                            + c.combatController().state());
            h.assertTrue(
                    c.combatController().target().isEmpty(),
                    "target after the settings change: " + c.combatController().target());
            h.assertTrue(
                    !c.executionCoordinator().paused(),
                    "the task pause was not released: " + c.combatController().state());
            h.succeed();
        });
    }

    /** A dodge step must land on a supported block, never off the platform. */
    @GameTest(maxTicks = 200)
    public void dodgeNeverLeavesPlatform(GameTestHelper h) {
        GameTestSupport.platformAt(h, 2, 7);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 3, 3, 3);
        c.setCombatSettings(c.combatSettings().withProtection(ProtectionPolicy.SELF_ONLY));
        Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 6, 3, 3);
        zombie.setNoAi(true);
        h.runAfterDelay(10, () -> c.combatController().fight(zombie));
        h.runAfterDelay(80, () -> {
            h.assertTrue(c.getY() >= 2.9, "the companion left the platform: y " + c.getY());
            h.assertTrue(c.onGround() || c.getY() >= 3.0, "the companion fell off: y " + c.getY());
            zombie.kill(h.getLevel());
            c.combatController().stopFight();
            h.succeed();
        });
    }
}
