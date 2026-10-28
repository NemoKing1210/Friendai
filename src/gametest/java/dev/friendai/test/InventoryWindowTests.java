package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.execution.tool.InventoryAccess;
import dev.friendai.menu.CompanionInventoryLayout;
import dev.friendai.menu.CompanionInventoryMenu;
import dev.friendai.menu.FriendaiMenus;
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
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        companion.inventory().addItem(new ItemStack(Items.STONE, 5));

        InventoryAccess.Denial denial = FriendaiMenus.open(owner, companion);
        h.assertTrue(denial == InventoryAccess.Denial.NONE, "open denied: " + denial);
        AbstractContainerMenu menu = owner.containerMenu;
        h.assertTrue(menu instanceof CompanionInventoryMenu, "menu was " + menu);
        h.assertTrue(
                companion.executionCoordinator().held(), "the companion must stand still while the window is open");

        // The companion's first inventory slot is menu slot 6: shift-clicking it fills the player's inventory.
        ItemStack moved = menu.quickMoveStack(owner, CompanionInventoryMenu.GEAR_SLOTS);
        h.assertTrue(!moved.isEmpty(), "the stack did not move");
        h.assertValueEqual(companion.inventory().countItem(Items.STONE), 0, "stone left in the companion");
        h.assertValueEqual(owner.getInventory().countItem(Items.STONE), 5, "stone in the player inventory");

        int from = -1;
        for (int index = CompanionInventoryMenu.GEAR_SLOTS + CompanionInventoryMenu.COMPANION_SLOTS;
                index < menu.slots.size();
                index++) {
            if (menu.slots.get(index).getItem().is(Items.STONE)) {
                from = index;
                break;
            }
        }
        h.assertTrue(from >= 0, "the stone is in no player slot");
        h.assertTrue(!menu.quickMoveStack(owner, from).isEmpty(), "the stack did not move back");
        h.assertValueEqual(companion.inventory().countItem(Items.STONE), 5, "stone back in the companion");

        owner.closeContainer();
        h.assertTrue(!companion.executionCoordinator().held(), "the companion must move again when the window closes");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void onlyTheOwnerOpensIt(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        ServerPlayer other = GameTestSupport.mockPlayerAt(h, 3, 2, 2);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);

        h.assertTrue(
                FriendaiMenus.open(other, companion) == InventoryAccess.Denial.NOT_OWNER,
                "a non-owner must not open the window");
        h.assertTrue(other.containerMenu == other.inventoryMenu, "a window opened for a non-owner");
        h.assertTrue(!companion.executionCoordinator().held(), "a denied open must not hold the companion");

        h.assertTrue(FriendaiMenus.open(owner, companion) == InventoryAccess.Denial.NONE, "the owner was denied");
        Slot helmet = owner.containerMenu.slots.get(0);
        h.assertTrue(helmet.mayPlace(new ItemStack(Items.IRON_HELMET)), "a helmet must fit the head slot");
        h.assertTrue(!helmet.mayPlace(new ItemStack(Items.IRON_CHESTPLATE)), "a chestplate must not fit the head slot");
        h.assertTrue(!helmet.mayPlace(new ItemStack(Items.STONE)), "stone must not fit the head slot");
        h.assertTrue(
                owner.containerMenu.slots.get(4).mayPlace(new ItemStack(Items.STONE)), "the main hand takes anything");
        owner.closeContainer();
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void menuPlacesSlotsLikeTheVanillaInventory(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        h.assertTrue(FriendaiMenus.open(owner, companion) == InventoryAccess.Denial.NONE, "the owner was denied");
        AbstractContainerMenu menu = owner.containerMenu;
        int pitch = CompanionInventoryLayout.PITCH;

        // Armor in a column on the left, as in the player inventory.
        h.assertValueEqual(menu.slots.get(0).x, CompanionInventoryLayout.SLOT_X, "helmet x");
        h.assertValueEqual(menu.slots.get(0).y, CompanionInventoryLayout.GEAR_Y, "helmet y");
        h.assertValueEqual(menu.slots.get(1).y, CompanionInventoryLayout.GEAR_Y + pitch, "chestplate y");
        h.assertValueEqual(menu.slots.get(3).y, CompanionInventoryLayout.GEAR_Y + 3 * pitch, "boots y");
        // The two hands sit together under the model.
        h.assertValueEqual(menu.slots.get(4).x, CompanionInventoryLayout.HANDS_X, "main hand x");
        h.assertValueEqual(menu.slots.get(5).x, CompanionInventoryLayout.HANDS_X + pitch, "off hand x");
        h.assertValueEqual(menu.slots.get(4).y, CompanionInventoryLayout.HANDS_Y, "hand y");
        // The companion grid, the player's grid and the hotbar are three rows each, one pitch apart.
        int companionStart = CompanionInventoryMenu.GEAR_SLOTS;
        h.assertValueEqual(menu.slots.get(companionStart).x, CompanionInventoryLayout.SLOT_X, "companion grid x");
        h.assertValueEqual(menu.slots.get(companionStart).y, CompanionInventoryLayout.ITEMS_Y, "companion grid y");
        h.assertValueEqual(
                menu.slots.get(companionStart + 9).y,
                CompanionInventoryLayout.ITEMS_Y + pitch,
                "companion grid second row");
        int playerStart = companionStart + CompanionInventoryMenu.COMPANION_SLOTS;
        h.assertValueEqual(menu.slots.get(playerStart).y, CompanionInventoryLayout.PLAYER_Y, "player grid y");
        h.assertValueEqual(
                menu.slots.get(playerStart + 9).y, CompanionInventoryLayout.PLAYER_Y + pitch, "player grid second row");
        h.assertValueEqual(menu.slots.size() - 9, playerStart + 27, "hotbar start");
        h.assertValueEqual(menu.slots.get(playerStart + 27).y, CompanionInventoryLayout.HOTBAR_Y, "hotbar y");

        owner.closeContainer();
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void hungerReachesTheSyncedField(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        companion.hunger().load(7, 0.0f, 0.0f);
        companion.tick();
        h.assertValueEqual(companion.syncedFood(), 7, "food must reach the field the client reads");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void goneCompanionCannotBeOpened(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        UUID ownerId = owner.getUUID();
        companion.setOwner(ownerId);
        companion.discard();
        h.assertTrue(
                FriendaiMenus.open(owner, companion) == InventoryAccess.Denial.GONE,
                "a discarded companion must not open");
        h.succeed();
    }
}
