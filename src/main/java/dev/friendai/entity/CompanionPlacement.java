package dev.friendai.entity;

import dev.friendai.features.construction.PlacementPace;
import net.minecraft.world.item.ItemStack;

/**
 * How fast the companion places blocks, and what that looks like: one pace shared by every task that places in bulk,
 * plus the short window in which the block being placed is visible in its hand.
 *
 * <p>Ticked by the entity on the server thread. Nothing here is saved: a reload starts ready, which is what a player
 * standing at the spot would be.
 */
public final class CompanionPlacement {
    /** One arm swing. The block stays in the hand for exactly that long, and a bulk build keeps it there. */
    public static final int POSE_TICKS = 6;

    private final CompanionEntity companion;
    private final PlacementPace pace = new PlacementPace();
    private int poseTicks;

    public CompanionPlacement(CompanionEntity companion) {
        this.companion = companion;
    }

    /** Whether the next block may be placed now. */
    public boolean ready() {
        return pace.ready();
    }

    /**
     * A block just appeared: wait the configured interval, and show {@code shown} in the hand for one swing. The stack
     * is a display copy from the block item, never the item that was taken out of the inventory.
     */
    public void placed(ItemStack shown) {
        pace.spent(PlacementPace.interval(
                companion.serverSettings().tasks().placeIntervalTicks(), companion.creativeMode()));
        show(shown);
        poseTicks = POSE_TICKS;
    }

    public void tick() {
        pace.tick();
        if (poseTicks > 0 && --poseTicks == 0) {
            show(ItemStack.EMPTY);
        }
    }

    /** Forgets the pace and the pose: a stopped or finished job leaves nothing behind for the next one. */
    public void clear() {
        pace.reset();
        poseTicks = 0;
        show(ItemStack.EMPTY);
    }

    /** How many ticks the next placement still has to wait; exposed for tests and diagnostics. */
    public int ticksLeft() {
        return pace.ticksLeft();
    }

    private void show(ItemStack stack) {
        if (ItemStack.matches(companion.syncedPlaceItem(), stack)) {
            // The same block is already in the hand: do not spend a packet per block on a bulk build.
            return;
        }
        companion.setSyncedPlaceItem(stack);
    }
}
