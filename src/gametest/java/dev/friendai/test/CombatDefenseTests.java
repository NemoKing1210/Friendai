package dev.friendai.test;

import dev.friendai.combat.ProtectionPolicy;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.task.combat.CombatController;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Shield, evasion and cleanup behaviour of the server-side arbiter. */
public class CombatDefenseTests {

    /** A displaced offhand stack comes back after the fight, and the shield is not duplicated. */
    @GameTest(maxTicks = 200)
    public void shieldRestoresOffhandOnStop(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 3, 2, 3);
        c.setCombatSettings(c.combatSettings().withStyle(dev.friendai.combat.FightingStyle.CAUTIOUS));
        c.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TORCH));
        c.inventory().addItem(new ItemStack(Items.SHIELD));
        Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 4, 2, 3);
        zombie.setNoAi(true);
        c.combatController().fight(zombie);
        h.runAfterDelay(40, () -> {
            h.assertTrue(c.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD),
                    "cautious melee did not raise a shield: " + c.getItemBySlot(EquipmentSlot.OFFHAND)
                            + ", state " + c.combatController().state());
            c.combatController().stopFight();
        });
        h.runAfterDelay(42, () -> {
            h.assertTrue(c.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.TORCH),
                    "the torch was not restored: " + c.getItemBySlot(EquipmentSlot.OFFHAND));
            int shields = c.inventory().countItem(Items.SHIELD);
            h.assertValueEqual(shields, 1, "shield count changed");
            h.assertTrue(c.combatController().state() == CombatController.State.IDLE,
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
        zombie.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(100.0);
        zombie.setHealth(100.0f);
        c.taskRunner().start(new dev.friendai.task.builtin.StayTask(), "stay");
        h.runAfterDelay(5, () -> c.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(zombie), 1.0f));
        h.runAfterDelay(30, () -> {
            h.assertTrue(c.combatController().state() == CombatController.State.FIGHTING,
                    "the reflex fight did not start: " + c.combatController().state());
            h.assertTrue(c.taskRunner().paused(), "the runner was not paused by the reflex fight");
            c.setCombatSettings(c.combatSettings().withProtection(ProtectionPolicy.AVOID_COMBAT));
        });
        h.runAfterDelay(40, () -> zombie.kill(h.getLevel()));
        h.runAfterDelay(120, () -> {
            h.assertTrue(c.combatController().state() == CombatController.State.IDLE,
                    "the old fight survived the settings change: " + c.combatController().state());
            h.assertTrue(c.combatController().target().isEmpty(),
                    "target after the settings change: " + c.combatController().target());
            h.assertTrue(!c.taskRunner().paused(),
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
