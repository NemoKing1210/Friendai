package dev.friendai.task.interact;

import com.mojang.authlib.GameProfile;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.inventory.InventoryOps;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The companion's "hands": one Fabric fake player per companion, so vanilla code performs placements and
 * right-clicks (orientation, sounds, block entities) and protection sees a player.
 *
 * <p>Anything left in the fake player's inventory after an action goes back to the companion, so items are never
 * lost or duplicated.
 */
public final class CompanionHands {
    private final CompanionEntity companion;
    private FakePlayer player;
    private @Nullable Merchant openMerchant;
    private boolean merchantSessionAcquired;
    private Predicate<BlockPos> protectionOverride;

    public CompanionHands(CompanionEntity companion) {
        this.companion = companion;
    }

    /** Test hook: positions matching the predicate count as protected, like spawn protection would. Null clears. */
    public void overrideProtection(Predicate<BlockPos> isProtected) {
        this.protectionOverride = isProtected;
    }

    /** Whether the companion may act on pos: the per-companion test hook and the level's own protection. */
    public boolean canInteract(BlockPos pos) {
        if (protectionOverride != null && protectionOverride.test(pos)) {
            return false;
        }
        return level().mayInteract(player(), pos);
    }

    /**
     * Places one item of {@code stack} against {@code face} of {@code against}. The whole stack is handed to the
     * fake player; whatever it does not use comes back to the companion.
     */
    public InteractionResult place(ItemStack stack, BlockPos against, Direction face) {
        return place(stack, against, face, true);
    }

    /** Uses a temporary creative item that must never be inserted into either inventory. */
    public InteractionResult placeTemporary(ItemStack stack, BlockPos against, Direction face) {
        if (!companion.creativeMode()) {
            throw new IllegalStateException("temporary placement items require creative mode");
        }
        return place(stack, against, face, false);
    }

    private InteractionResult place(ItemStack stack, BlockPos against, Direction face, boolean returnRemainder) {
        ServerLevel level = level();
        FakePlayer hands = handsPlayer();
        Vec3 hitPoint = Vec3.atCenterOf(against).relative(face, 0.5);
        sync(hands, hitPoint);
        ItemStack held = stack.copy();
        hands.setItemInHand(InteractionHand.MAIN_HAND, held);
        BlockHitResult hit = new BlockHitResult(hitPoint, face, against, false);
        try {
            return hands.gameMode.useItemOn(hands, level, held, InteractionHand.MAIN_HAND, hit);
        } finally {
            if (returnRemainder) {
                returnItems(hands);
            } else {
                hands.getInventory().clearContent();
                hands.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            }
        }
    }

    /**
     * Right-clicks the entity with {@code stack} in the fake player's main hand; the remainder returns to the
     * companion.
     */
    public InteractionResult interact(Entity target, ItemStack stack) {
        ServerLevel level = level();
        FakePlayer hands = handsPlayer();
        sync(hands, target.getEyePosition());
        ItemStack held = stack.copy();
        hands.setItemInHand(InteractionHand.MAIN_HAND, held);
        Vec3 hitPoint = target.position().add(0.0, target.getBbHeight() * 0.5, 0.0);
        try {
            return hands.interactOn(target, InteractionHand.MAIN_HAND, hitPoint);
        } finally {
            returnItems(hands);
        }
    }

    /**
     * Right-clicks in the air with the stack in the fake player's hand and aims at {@code aim} — how a player places
     * water, drinks milk or throws a potion. Whatever is left in the fake player's hands comes back to the
     * companion, so a refused use never loses the item.
     */
    public InteractionResult useItem(ItemStack stack, Vec3 aim) {
        ServerLevel level = level();
        FakePlayer hands = handsPlayer();
        sync(hands, aim);
        ItemStack held = stack.copy();
        hands.setItemInHand(InteractionHand.MAIN_HAND, held);
        try {
            return hands.gameMode.useItem(hands, level, held, InteractionHand.MAIN_HAND);
        } finally {
            returnItems(hands);
        }
    }

    /** Right-clicks pos with an empty hand. */
    public InteractionResult use(BlockPos pos) {
        ServerLevel level = level();
        FakePlayer hands = handsPlayer();
        BlockState state = level.getBlockState(pos);
        Direction face = faceToward(pos, hands);
        Vec3 hitPoint = Vec3.atCenterOf(pos).relative(face, 0.5);
        sync(hands, hitPoint);
        hands.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        BlockHitResult hit = new BlockHitResult(hitPoint, face, pos, false);
        try {
            return state.useWithoutItem(level, hands, hit);
        } finally {
            returnItems(hands);
        }
    }

    /**
     * Right-clicks pos with an empty hand and returns the vanilla menu that opened for the fake player, or null when
     * none did. The companion's experience is mirrored into the fake player first, so menus that charge levels see
     * them; {@link #closeMenu()} copies it back.
     */
    public @Nullable AbstractContainerMenu openMenu(BlockPos pos) {
        FakePlayer hands = handsPlayer();
        mirrorExperienceIn(hands);
        use(pos);
        if (hands.containerMenu == hands.inventoryMenu) {
            // Fabric's FakePlayer swallows openMenu, so install the block's menu by hand; the menu works server-side
            // without the open-screen packet.
            MenuProvider provider = level().getBlockState(pos).getMenuProvider(level(), pos);
            AbstractContainerMenu menu = provider == null ? null : provider.createMenu(1, hands.getInventory(), hands);
            if (menu == null) {
                return null;
            }
            hands.containerMenu = menu;
        }
        return hands.containerMenu;
    }

    /**
     * Opens a merchant as this companion's own acting player. Vanilla interaction initializes personalized prices;
     * only Fabric's swallowed screen opening is replaced. The caller must close the session in a finally block.
     */
    public @Nullable MerchantMenu openMerchant(Entity target) {
        FakePlayer hands = handsPlayer();
        if (!(target instanceof Merchant merchant) || target.level() != level() || !target.isAlive()
                || merchant.getTradingPlayer() != null || hands.containerMenu != hands.inventoryMenu
                || !canInteract(target.blockPosition())) {
            return null;
        }
        sync(hands, target.getEyePosition());
        if (!hands.isWithinEntityInteractionRange(target, 0.0)) {
            return null;
        }
        mirrorExperienceIn(hands);
        openMerchant = merchant;
        merchantSessionAcquired = false;
        boolean opened = false;
        try {
            hands.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            // The Fabric callback normally lives in the network handler, which these server-side hands bypass.
            InteractionResult event = UseEntityCallback.EVENT.invoker()
                    .interact(hands, level(), InteractionHand.MAIN_HAND, target,
                            new EntityHitResult(target, target.position().add(0.0, target.getBbHeight() * 0.5, 0.0)));
            if (event != InteractionResult.PASS) {
                return null;
            }
            interact(target, ItemStack.EMPTY);
            if (merchant.getTradingPlayer() != hands) {
                return null;
            }
            merchantSessionAcquired = true;
            if (hands.containerMenu == hands.inventoryMenu) {
                hands.containerMenu = new MerchantMenu(1, hands.getInventory(), merchant);
            }
            if (!(hands.containerMenu instanceof MerchantMenu menu) || !menu.stillValid(hands)) {
                return null;
            }
            menu.setCanRestock(merchant.canRestock());
            menu.setShowProgressBar(merchant.showProgressBar());
            opened = true;
            return menu;
        } finally {
            if (!opened) {
                closeMenu();
            }
        }
    }

    /**
     * Closes the fake player's menu: vanilla hands the menu's input slots back to the fake player, everything it holds
     * returns to the companion, and block-menu experience costs are copied back. Merchant XP stays on the companion.
     */
    public void closeMenu() {
        if (player == null) {
            return;
        }
        FakePlayer hands = player;
        Merchant merchant = openMerchant;
        boolean acquiredMerchant = merchantSessionAcquired;
        openMerchant = null;
        merchantSessionAcquired = false;
        boolean ownsMerchant = merchant != null && merchant.getTradingPlayer() == hands;
        boolean hasMenu = hands.containerMenu != hands.inventoryMenu;
        if (!hasMenu && merchant == null) {
            return;
        }
        try {
            if (hands.containerMenu instanceof MerchantMenu && merchant != null && !ownsMerchant) {
                // MerchantMenu.removed unconditionally clears the customer (and their special prices). A stale
                // menu may only return its own unpaid inputs; its result is a preview, never an owned item.
                for (int slot = 0; slot < 2; slot++) {
                    ItemStack input = hands.containerMenu.getSlot(slot).remove(Integer.MAX_VALUE);
                    hands.getInventory().placeItemBackInInventory(input, Prediction.SERVER_ONLY);
                }
                ItemStack carried = hands.containerMenu.getCarried();
                hands.containerMenu.setCarried(ItemStack.EMPTY);
                hands.getInventory().placeItemBackInInventory(carried, Prediction.SERVER_ONLY);
                hands.containerMenu = hands.inventoryMenu;
            } else if (hasMenu) {
                hands.closeContainer();
            }
        } finally {
            if (merchant != null && merchant.getTradingPlayer() == hands) {
                merchant.setTradingPlayer(null);
            }
            if (merchant != null && (acquiredMerchant || ownsMerchant) && merchant.getTradingPlayer() == null) {
                // 26.3 recalculates special prices on open, but leaves them on offers after vanilla close.
                merchant.getOffers().forEach(offer -> offer.resetSpecialPriceDiff());
            }
            returnItems(hands);
            if (merchant == null) {
                // Block menus can spend levels. Merchant rewards are orbs collected by the companion itself, so
                // copying the merchant's old mirrored snapshot back would erase XP picked up while it was open.
                companion.experience().loadLevel(hands.experienceLevel, hands.experienceProgress);
            }
            companion.syncExperience();
        }
    }

    private void mirrorExperienceIn(FakePlayer hands) {
        hands.experienceLevel = companion.experience().level();
        hands.experienceProgress = companion.experience().progress();
        hands.totalExperience = companion.experience().points();
    }

    /** The fake player, for container open/close animations. */
    public ServerPlayer player() {
        return handsPlayer();
    }

    private FakePlayer handsPlayer() {
        ServerLevel level = level();
        if (player == null || player.level() != level) {
            UUID uuid = UUID.nameUUIDFromBytes(("friendai-hands:" + companion.getUUID()).getBytes(StandardCharsets.UTF_8));
            player = FakePlayer.get(level, new GameProfile(uuid, "Friendai"));
        }
        return player;
    }

    private ServerLevel level() {
        return (ServerLevel) companion.level();
    }

    private void sync(FakePlayer hands, Vec3 lookAt) {
        Vec3 feet = companion.position();
        hands.snapTo(feet.x, feet.y, feet.z);
        hands.setDeltaMovement(Vec3.ZERO);
        Vec3 eye = hands.getEyePosition();
        double dx = lookAt.x - eye.x;
        double dy = lookAt.y - eye.y;
        double dz = lookAt.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        hands.setYRot(yaw);
        hands.setXRot(pitch);
        hands.setYHeadRot(yaw);
        GameType desiredGameMode = companion.creativeMode() ? GameType.CREATIVE : GameType.SURVIVAL;
        if (hands.gameMode.getGameModeForPlayer() != desiredGameMode) {
            hands.setGameMode(desiredGameMode);
        }
        companion.getLookControl().setLookAt(lookAt.x, lookAt.y, lookAt.z);
        companion.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
    }

    private void returnItems(FakePlayer hands) {
        Inventory inventory = hands.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                InventoryOps.insertOrDrop(companion, stack.copy());
            }
        }
        inventory.clearContent();
        hands.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }

    private static Direction faceToward(BlockPos pos, ServerPlayer player) {
        Vec3 direction = player.getEyePosition().subtract(Vec3.atCenterOf(pos));
        double x = Math.abs(direction.x);
        double y = Math.abs(direction.y);
        double z = Math.abs(direction.z);
        if (y > x && y > z) {
            return direction.y > 0 ? Direction.UP : Direction.DOWN;
        }
        if (x > z) {
            return direction.x > 0 ? Direction.EAST : Direction.WEST;
        }
        return direction.z > 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
