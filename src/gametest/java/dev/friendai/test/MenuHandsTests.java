package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.survival.ExperienceModel;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** The companion's hands open vanilla menus with the companion's experience mirrored into the fake player. */
public class MenuHandsTests {
    private static final int BAND_Y = 108;

    private static CompanionEntity setup(GameTestHelper h, int bandY, Block block) {
        GameTestSupport.platformAt(h, bandY, 12);
        h.setBlock(3, bandY + 1, 2, block);
        return h.spawn(FriendaiEntities.COMPANION, 2, bandY + 1, 2);
    }

    private static BlockPos blockPos(GameTestHelper h, int bandY) {
        return h.absolutePos(new BlockPos(3, bandY + 1, 2));
    }

    @GameTest
    public void opensEnchantingTable(GameTestHelper h) {
        CompanionEntity c = setup(h, BAND_Y, Blocks.ENCHANTING_TABLE);
        AbstractContainerMenu menu = c.hands().openMenu(blockPos(h, BAND_Y));
        h.assertTrue(menu instanceof EnchantmentMenu, "menu was " + menu);
        c.hands().closeMenu();
        h.succeed();
    }

    @GameTest
    public void opensAnvil(GameTestHelper h) {
        CompanionEntity c = setup(h, 116, Blocks.ANVIL);
        BlockPos pos = blockPos(h, 116);
        AbstractContainerMenu menu = c.hands().openMenu(pos);
        h.assertTrue(menu instanceof AnvilMenu, "menu was " + menu);
        c.hands().closeMenu();
        h.succeed();
    }

    @GameTest
    public void opensGrindstone(GameTestHelper h) {
        CompanionEntity c = setup(h, 160, Blocks.GRINDSTONE);
        AbstractContainerMenu menu = c.hands().openMenu(blockPos(h, 160));
        h.assertTrue(menu instanceof GrindstoneMenu, "menu was " + menu);
        c.hands().closeMenu();
        h.succeed();
    }

    @GameTest
    public void mirrorsExperience(GameTestHelper h) {
        CompanionEntity c = setup(h, 166, Blocks.ENCHANTING_TABLE);
        c.experience().load(ExperienceModel.pointsForLevel(30));
        c.syncExperience();
        AbstractContainerMenu menu = c.hands().openMenu(blockPos(h, 166));
        h.assertTrue(menu != null, "the menu did not open");
        h.assertTrue(
                c.hands().player().experienceLevel == 30,
                "fake player level was " + c.hands().player().experienceLevel);
        c.hands().player().experienceLevel = 27;
        c.hands().closeMenu();
        h.assertTrue(
                c.experience().level() == 27,
                "companion level was " + c.experience().level());
        h.assertTrue(c.syncedXpLevel() == 27, "synced level was " + c.syncedXpLevel());
        h.succeed();
    }

    @GameTest
    public void closeReturnsItems(GameTestHelper h) {
        CompanionEntity c = setup(h, 170, Blocks.ENCHANTING_TABLE);
        AbstractContainerMenu menu = c.hands().openMenu(blockPos(h, 170));
        h.assertTrue(menu != null, "the menu did not open");
        menu.getSlot(0).set(new ItemStack(Items.DIAMOND_SWORD));
        c.hands().closeMenu();
        boolean found = false;
        for (int i = 0; i < c.inventory().getContainerSize(); i++) {
            if (c.inventory().getItem(i).is(Items.DIAMOND_SWORD)) {
                found = true;
            }
        }
        h.assertTrue(found, "the sword did not come back to the companion");
        h.succeed();
    }

    @GameTest
    public void closeWithoutMenuIsHarmless(GameTestHelper h) {
        CompanionEntity c = setup(h, 210, Blocks.ENCHANTING_TABLE);
        c.experience().load(50);
        c.hands().closeMenu();
        h.assertTrue(
                c.experience().points() == 50, "points were " + c.experience().points());
        h.succeed();
    }
}
