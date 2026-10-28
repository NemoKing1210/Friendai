package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;

/** Bow combat, ammunition accounting and item restoration. */
public class CombatRangedTests {

    private static ItemStack infinityBow(GameTestHelper h) {
        ItemStack bow = new ItemStack(Items.BOW);
        bow.enchant(
                h.getLevel()
                        .registryAccess()
                        .lookupOrThrow(Registries.ENCHANTMENT)
                        .getOrThrow(Enchantments.INFINITY),
                1);
        return bow;
    }

    private static int flyingArrows(GameTestHelper h, CompanionEntity c) {
        return h
                .getLevel()
                .getEntitiesOfClass(AbstractArrow.class, c.getBoundingBox().inflate(32.0))
                .stream()
                .filter(arrow -> arrow.getOwner() == c)
                .mapToInt(arrow -> 1)
                .sum();
    }

    private static int arrowsInWorld(GameTestHelper h, CompanionEntity c) {
        return c.inventory().countItem(Items.ARROW) + flyingArrows(h, c);
    }

    @GameTest(maxTicks = 500)
    public void lastArrowConsumedOnce(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.BOW));
        c.inventory().addItem(new ItemStack(Items.ARROW, 1));
        Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 13, 2, 2);
        zombie.setNoAi(true);
        h.runAfterDelay(
                20,
                () -> c.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(zombie), 1.0f));
        h.succeedWhen(() -> {
            h.assertTrue(c.inventory().countItem(Items.ARROW) == 0 || flyingArrows(h, c) > 0, "waiting for the shot");
            h.assertValueEqual(c.inventory().countItem(Items.ARROW), 0, "the last arrow was not consumed exactly once");
            h.assertValueEqual(flyingArrows(h, c), 1, "the last arrow was duplicated");
        });
    }

    @GameTest(maxTicks = 500)
    public void infinityRequiresAnArrowAndPreservesIt(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(infinityBow(h));
        c.inventory().addItem(new ItemStack(Items.ARROW, 1));
        Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 13, 2, 2);
        zombie.setNoAi(true);
        h.runAfterDelay(
                20,
                () -> c.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(zombie), 1.0f));
        h.succeedWhen(() -> {
            h.assertTrue(flyingArrows(h, c) > 0, "waiting for the Infinity shot");
            h.assertValueEqual(
                    c.inventory().countItem(Items.ARROW),
                    1,
                    "Infinity consumed the only arrow, which vanilla does not do for a normal arrow");
        });
    }

    @GameTest(maxTicks = 400)
    public void bowBreakDuringDrawFallsBackWithoutLosingArrows(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ItemStack bow = new ItemStack(Items.BOW);
        bow.setDamageValue(bow.getMaxDamage() - 1);
        c.inventory().addItem(bow);
        c.inventory().addItem(new ItemStack(Items.ARROW, 3));
        c.inventory().addItem(new ItemStack(Items.STONE_SWORD));
        Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 13, 2, 2);
        zombie.setNoAi(true);
        zombie.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100.0);
        zombie.setHealth(100.0f);
        h.runAfterDelay(
                20,
                () -> c.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(zombie), 1.0f));
        h.runAfterDelay(240, () -> {
            h.assertTrue(c.inventory().countItem(Items.ARROW) <= 3, "arrows were duplicated");
            h.assertTrue(c.inventory().countItem(Items.ARROW) >= 0, "arrows went negative");
            h.assertTrue(
                    !c.getMainHandItem().is(Items.BOW),
                    "the broken bow stayed held: " + c.getMainHandItem() + ", state "
                            + c.combatController().state());
            h.assertTrue(
                    c.getMainHandItem().is(Items.STONE_SWORD)
                            || c.getMainHandItem().isEmpty(),
                    "no melee fallback: " + c.getMainHandItem());
            zombie.kill(h.getLevel());
            c.combatController().stopFight();
            h.succeed();
        });
    }

    @GameTest(maxTicks = 300)
    public void fullInventoryDoesNotDuplicateWeapon(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.STONE_SWORD));
        for (int i = 1; i < c.inventory().getContainerSize(); i++) {
            c.inventory().addItem(new ItemStack(Items.DIRT, 64));
        }
        Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 5, 2, 2);
        zombie.setNoAi(true);
        zombie.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100.0);
        zombie.setHealth(100.0f);
        c.combatController().fight(zombie);
        h.runAfterDelay(20, () -> c.combatController().stopFight());
        h.runAfterDelay(22, () -> {
            int swords = c.inventory().countItem(Items.STONE_SWORD)
                    + h
                            .getLevel()
                            .getEntitiesOfClass(
                                    net.minecraft.world.entity.item.ItemEntity.class,
                                    c.getBoundingBox().inflate(8.0))
                            .stream()
                            .filter(item -> item.getItem().is(Items.STONE_SWORD))
                            .mapToInt(item -> item.getItem().getCount())
                            .sum();
            h.assertValueEqual(swords, 1, "the weapon was duplicated or lost with a full inventory");
            h.assertTrue(
                    c.getMainHandItem().isEmpty(), "the previous empty hand was not restored: " + c.getMainHandItem());
            zombie.kill(h.getLevel());
            h.succeed();
        });
    }
}
