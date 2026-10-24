package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.menu.CompanionInventoryMenu;
import dev.friendai.menu.FriendaiMenus;
import dev.friendai.tool.InventoryAccess;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class InventoryWindowTests {
    @GameTest(maxTicks = 200)
    public void ownerOpensAndMovesItems(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = CompanionManager.get(h.getLevel().getServer()).summon(owner);
        companion.inventory().addItem(new ItemStack(Items.STONE, 5));

        InventoryAccess.Denial denial = FriendaiMenus.open(owner, companion);
        h.assertTrue(denial == InventoryAccess.Denial.NONE, "open denied: " + denial);
        AbstractContainerMenu menu = owner.containerMenu;
        h.assertTrue(menu instanceof CompanionInventoryMenu, "menu was " + menu);
        h.assertTrue(companion.taskRunner().held(), "the companion must stand still while the window is open");

        // The companion's first inventory slot is menu slot 6: shift-clicking it fills the player's inventory.
        ItemStack moved = menu.quickMoveStack(owner, CompanionInventoryMenu.GEAR_SLOTS);
        h.assertTrue(!moved.isEmpty(), "the stack did not move");
        h.assertValueEqual(companion.inventory().countItem(Items.STONE), 0, "stone left in the companion");
        h.assertValueEqual(owner.getInventory().countItem(Items.STONE), 5, "stone in the player inventory");

        int from = -1;
        for (int index = CompanionInventoryMenu.GEAR_SLOTS + CompanionInventoryMenu.COMPANION_SLOTS;
                index < menu.slots.size(); index++) {
            if (menu.slots.get(index).getItem().is(Items.STONE)) {
                from = index;
                break;
            }
        }
        h.assertTrue(from >= 0, "the stone is in no player slot");
        h.assertTrue(!menu.quickMoveStack(owner, from).isEmpty(), "the stack did not move back");
        h.assertValueEqual(companion.inventory().countItem(Items.STONE), 5, "stone back in the companion");

        owner.closeContainer();
        h.assertTrue(!companion.taskRunner().held(), "the companion must move again when the window closes");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void onlyTheOwnerOpensIt(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        ServerPlayer other = GameTestSupport.mockPlayerAt(h, 3, 2, 2);
        CompanionEntity companion = CompanionManager.get(h.getLevel().getServer()).summon(owner);

        h.assertTrue(FriendaiMenus.open(other, companion) == InventoryAccess.Denial.NOT_OWNER,
                "a non-owner must not open the window");
        h.assertTrue(other.containerMenu == other.inventoryMenu, "a window opened for a non-owner");
        h.assertTrue(!companion.taskRunner().held(), "a denied open must not hold the companion");

        h.assertTrue(FriendaiMenus.open(owner, companion) == InventoryAccess.Denial.NONE, "the owner was denied");
        Slot helmet = owner.containerMenu.slots.get(0);
        h.assertTrue(helmet.mayPlace(new ItemStack(Items.IRON_HELMET)), "a helmet must fit the head slot");
        h.assertTrue(!helmet.mayPlace(new ItemStack(Items.IRON_CHESTPLATE)), "a chestplate must not fit the head slot");
        h.assertTrue(!helmet.mayPlace(new ItemStack(Items.STONE)), "stone must not fit the head slot");
        h.assertTrue(owner.containerMenu.slots.get(4).mayPlace(new ItemStack(Items.STONE)),
                "the main hand takes anything");
        owner.closeContainer();
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void goneCompanionCannotBeOpened(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = CompanionManager.get(h.getLevel().getServer()).summon(owner);
        UUID ownerId = owner.getUUID();
        companion.setOwner(ownerId);
        companion.discard();
        h.assertTrue(FriendaiMenus.open(owner, companion) == InventoryAccess.Denial.GONE,
                "a discarded companion must not open");
        h.succeed();
    }
}
