package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.task.combat.Equipment;
import dev.friendai.task.mining.ToolSelector;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** What the companion holds: the right tool for a block, the right weapon for a fight. */
public class ToolChoiceTests {
    @GameTest
    public void putsAwayAToolThatDoesNotHelp(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 1, 2, 1);
        c.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));

        ToolSelector.equipBest(c, Blocks.OAK_LOG.defaultBlockState());

        h.assertTrue(c.getMainHandItem().isEmpty(), "still holding " + c.getMainHandItem());
        h.assertValueEqual(1, c.inventory().countItem(Items.IRON_PICKAXE), "pickaxes in the inventory");
        h.succeed();
    }

    @GameTest
    public void holdsTheAxeForWood(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 1, 2, 1);
        c.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
        c.inventory().addItem(new ItemStack(Items.IRON_AXE));

        ToolSelector.equipBest(c, Blocks.OAK_LOG.defaultBlockState());

        h.assertTrue(c.getMainHandItem().is(Items.IRON_AXE), "holding " + c.getMainHandItem());
        h.assertValueEqual(1, c.inventory().countItem(Items.IRON_PICKAXE), "pickaxes back in the inventory");
        h.succeed();
    }

    @GameTest
    public void holdsThePickaxeForStone(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 1, 2, 1);
        c.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
        c.inventory().addItem(new ItemStack(Items.IRON_PICKAXE));

        ToolSelector.equipBest(c, Blocks.STONE.defaultBlockState());

        h.assertTrue(c.getMainHandItem().is(Items.IRON_PICKAXE), "holding " + c.getMainHandItem());
        h.assertValueEqual(1, c.inventory().countItem(Items.IRON_AXE), "axes back in the inventory");
        h.succeed();
    }

    @GameTest
    public void keepsTheBestToolAlreadyInHand(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 1, 2, 1);
        c.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_AXE));
        c.inventory().addItem(new ItemStack(Items.WOODEN_AXE));

        ToolSelector.equipBest(c, Blocks.OAK_LOG.defaultBlockState());

        h.assertTrue(c.getMainHandItem().is(Items.DIAMOND_AXE), "holding " + c.getMainHandItem());
        h.assertValueEqual(1, c.inventory().countItem(Items.WOODEN_AXE), "wooden axes in the inventory");
        h.succeed();
    }

    @GameTest
    public void prefersTheSwordOverAHigherTierShovel(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 1, 2, 1);
        c.inventory().addItem(new ItemStack(Items.DIAMOND_SHOVEL));
        c.inventory().addItem(new ItemStack(Items.STONE_SWORD));

        ItemStack best = Equipment.bestMeleeWeapon(c);

        h.assertTrue(best.is(Items.STONE_SWORD), "chose " + best);
        h.succeed();
    }

    @GameTest
    public void prefersTheSwordOverAnAxe(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 1, 2, 1);
        c.inventory().addItem(new ItemStack(Items.IRON_AXE));
        c.inventory().addItem(new ItemStack(Items.IRON_SWORD));

        ItemStack best = Equipment.bestMeleeWeapon(c);

        h.assertTrue(best.is(Items.IRON_SWORD), "chose " + best);
        h.succeed();
    }

    @GameTest
    public void keepsTheStrongerWeaponInHand(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 1, 2, 1);
        c.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
        c.inventory().addItem(new ItemStack(Items.IRON_SWORD));

        ItemStack best = Equipment.bestMeleeWeapon(c);

        h.assertTrue(best.isEmpty(), "swapped to " + best);
        h.succeed();
    }
}
