package dev.friendai.menu;

import dev.friendai.FriendaiMod;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.tool.InventoryAccess;
import java.util.Objects;
import java.util.OptionalInt;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** Registers the companion inventory window and opens it for the owner. */
public final class FriendaiMenus {
    /** The menu type; the extra data is the companion's entity id, so the client can find it. */
    public static final ExtendedMenuType<CompanionInventoryMenu, Integer> TYPE = Registry.register(
            BuiltInRegistries.MENU,
            FriendaiMod.id("companion_inventory"),
            new ExtendedMenuType<>(CompanionInventoryMenu::new, ByteBufCodecs.VAR_INT));

    private FriendaiMenus() {}

    /** Forces the static registration during mod init, before the menu registry freezes. */
    public static void register() {
        Objects.requireNonNull(TYPE);
    }

    /**
     * Opens the window for {@code player}. Returns {@link InventoryAccess.Denial#NONE} on success, otherwise the
     * reason nothing opened; the companion's runner is held while the window is open.
     */
    public static InventoryAccess.Denial open(ServerPlayer player, CompanionEntity companion) {
        InventoryAccess.Denial denial = InventoryAccess.check(
                player.getUUID().equals(companion.ownerUuid()),
                player.level() == companion.level(),
                companion.isAlive() && !companion.isRemoved(),
                player.distanceTo(companion));
        if (denial != InventoryAccess.Denial.NONE) {
            return denial;
        }
        companion.addInventoryViewer();
        OptionalInt opened = player.openMenu(new ExtendedMenuProvider<Integer>() {
            @Override
            public Component getDisplayName() {
                return companion.getName();
            }

            @Override
            public Integer getScreenOpeningData(ServerPlayer ignored) {
                return companion.getId();
            }

            @Override
            public AbstractContainerMenu createMenu(int id, Inventory inventory, Player viewer) {
                return new CompanionInventoryMenu(id, inventory, companion);
            }
        });
        if (opened.isEmpty()) {
            companion.removeInventoryViewer();
            return InventoryAccess.Denial.GONE;
        }
        return InventoryAccess.Denial.NONE;
    }
}
