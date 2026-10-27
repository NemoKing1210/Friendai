package dev.friendai.task.interact;

import com.mojang.authlib.GameProfile;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.inventory.InventoryOps;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
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
     * Closes the fake player's menu: vanilla hands the menu's input slots back to the fake player, everything it holds
     * returns to the companion, and the fake player's experience is copied back. Does nothing when no menu is open.
     */
    public void closeMenu() {
        if (player == null || player.containerMenu == player.inventoryMenu) {
            return;
        }
        FakePlayer hands = player;
        hands.closeContainer();
        returnItems(hands);
        companion.experience().loadLevel(hands.experienceLevel, hands.experienceProgress);
        companion.syncExperience();
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
