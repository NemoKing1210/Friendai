package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.task.combat.CombatController;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Melee spacing, weapon choice and action-time permission rechecks. */
public class CombatTacticsTests {

    @GameTest(maxTicks = 300)
    public void axeOnlyCombatUsesAxe(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.IRON_AXE));
        Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 5, 2, 2);
        zombie.setNoAi(true);
        zombie.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100.0);
        zombie.setHealth(100.0f);
        h.runAfterDelay(10, () -> c.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(zombie), 1.0f));
        h.succeedWhen(() -> {
            h.assertTrue(c.combatController().state() == CombatController.State.FIGHTING,
                    "the reflexive fight did not start: " + c.combatController().state());
            h.assertTrue(c.getMainHandItem().is(Items.IRON_AXE),
                    "the axe was not selected: " + c.getMainHandItem()
                            + ", inventory " + c.inventory().countItem(Items.IRON_AXE));
            zombie.kill(h.getLevel());
            c.combatController().stopFight();
        });
    }

    @GameTest(maxTicks = 200)
    public void weaponSwapKeepsOneCopyOnStop(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.IRON_SWORD));
        Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 5, 2, 2);
        zombie.setNoAi(true);
        zombie.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100.0);
        zombie.setHealth(100.0f);
        c.combatController().fight(zombie);
        h.runAfterDelay(10, () -> {
            h.assertTrue(c.getMainHandItem().is(Items.IRON_SWORD),
                    "the sword was not equipped: " + c.getMainHandItem());
            c.combatController().stopFight();
        });
        h.runAfterDelay(12, () -> {
            int swords = c.inventory().countItem(Items.IRON_SWORD)
                    + (c.getItemBySlot(EquipmentSlot.MAINHAND).is(Items.IRON_SWORD) ? 1 : 0);
            h.assertValueEqual(swords, 1, "the equipped sword was duplicated or lost");
            h.assertTrue(c.getMainHandItem().isEmpty(), "the empty main hand was not restored");
            zombie.kill(h.getLevel());
            h.succeed();
        });
    }

    @GameTest(maxTicks = 300)
    public void targetBecomingIneligibleBeforeMeleeHitIsNotHit(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        owner.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        ServerPlayer target = GameTestSupport.mockPlayerAt(h, 5, 2, 2);
        target.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 3, 2, 2);
        c.setOwner(owner.getUUID());
        c.inventory().addItem(new ItemStack(Items.STONE_SWORD));
        c.combatController().fight(target);
        float before = target.getHealth();
        h.runAfterDelay(20, () -> target.setGameMode(net.minecraft.world.level.GameType.CREATIVE));
        h.runAfterDelay(120, () -> {
            h.assertValueEqual(before, target.getHealth(), "an ineligible target was still hit");
            target.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            target.kill(h.getLevel());
            c.combatController().stopFight();
            h.succeed();
        });
    }

    @GameTest(maxTicks = 300)
    public void pursuitStopsAtConfiguredLimit(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setCombatSettings(c.combatSettings().withPursuitLimit(8));
        Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 12, 2, 2);
        zombie.setNoAi(true);
        h.runAfterDelay(10, () -> c.combatController().fight(zombie));
        h.runAfterDelay(40, () -> {
            h.assertTrue(c.combatController().state() == CombatController.State.IDLE,
                    "the companion pursued past the 8-block limit: " + c.combatController().state());
            h.assertTrue(c.combatController().target().isEmpty(),
                    "target after disengage: " + c.combatController().target());
            zombie.kill(h.getLevel());
            h.succeed();
        });
    }
}
