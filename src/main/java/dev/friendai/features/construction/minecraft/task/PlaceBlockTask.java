package dev.friendai.features.construction.minecraft.task;

import dev.friendai.debug.DebugAction;
import dev.friendai.debug.DebugTarget;
import dev.friendai.features.creative.CreativeAction;
import dev.friendai.features.navigation.minecraft.nav.Approach;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.features.places.PlaceCandidate;
import dev.friendai.features.places.PlaceKind;
import dev.friendai.features.places.PlaceSource;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.autonomy.ProtectionText;
import dev.friendai.task.interact.PlaceSpot;
import dev.friendai.task.inventory.CreativeItemLease;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/** Places one block from the inventory at the given coordinates, or at a free spot nearby. */
public final class PlaceBlockTask implements Task {
    private static final int NEARBY_RADIUS = 4;
    private final BlockItem item;
    private final BlockPos target;
    private final ItemMatcher matcher;
    private Approach approach;
    private BlockPos spot;
    private BlockPos against;
    private Direction face;
    private boolean prepared;
    private BlockPos placedAt;
    private String dimension = "";
    private String summary = "";

    public PlaceBlockTask(BlockItem item, BlockPos target) {
        this.item = item;
        this.target = target;
        this.matcher = ItemMatcher.parse(BuiltInRegistries.ITEM.getKey(item).toString())
                .orElseThrow();
    }
    /** Where the block ended up after a success, or null. */
    public BlockPos placedAt() {
        return placedAt;
    }
    /**
     * A chest the companion placed deliberately is a storage place, so free time can find it and unload into it
     * next time instead of asking the director for the same chest forever.
     */
    @Override
    public PlaceCandidate placeCandidate() {
        if (placedAt == null
                || dimension.isBlank()
                || item != Blocks.CHEST.asItem() && item != Blocks.TRAPPED_CHEST.asItem()) {
            return null;
        }
        return new PlaceCandidate(
                PlaceKind.STORAGE,
                "storage",
                "storage",
                dimension,
                new Pos(placedAt.getX(), placedAt.getY(), placedAt.getZ()),
                2,
                PlaceSource.BUILD);
    }

    private String itemId() {
        return matcher.label();
    }

    @Override
    public String type() {
        return "place_block";
    }

    @Override
    public List<DebugTarget> debugTargets() {
        BlockPos destination = spot != null ? spot : target;
        return destination == null
                ? List.of()
                : List.of(new DebugTarget(
                        destination.getX(), destination.getY(), destination.getZ(), DebugAction.PLACE, itemId()));
    }

    @Override
    public void start(TaskContext c) {
        dimension = c.level().dimension().identifier().toString();
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        var companion = c.companion();
        if (!prepared) {
            if (!hasItem(companion) && !c.canUseCreativeAction(CreativeAction.FREE_MATERIALS)) {
                summary = "I don't have " + itemId();
                return TaskStatus.FAILED;
            }
            if (target != null) {
                Optional<String> problem = PlaceSpot.problem(c.level(), target, companion);
                if (problem.isPresent()) {
                    summary = problem.get();
                    return TaskStatus.FAILED;
                }
                Optional<PlaceSpot.Spot> spot = PlaceSpot.spotAt(c.level(), target);
                if (spot.isEmpty()) {
                    summary = "nothing to place against at " + coords(target);
                    return TaskStatus.FAILED;
                }
                this.spot = target;
                this.against = spot.get().against();
                this.face = spot.get().face();
            } else {
                Optional<PlaceSpot.Spot> spot = PlaceSpot.near(c.level(), companion, NEARBY_RADIUS);
                if (spot.isEmpty()) {
                    summary = "no free spot to place " + itemId() + " nearby";
                    return TaskStatus.FAILED;
                }
                this.spot = spot.get().pos();
                this.against = spot.get().against();
                this.face = spot.get().face();
            }
            approach = new Approach(spot);
            prepared = true;
        }
        switch (approach.tick(c)) {
            case UNREACHABLE -> {
                summary = "can't reach " + coords(spot);
                return TaskStatus.FAILED;
            }
            case MOVING -> {
                return TaskStatus.RUNNING;
            }
            case IN_REACH -> {}
        }
        Optional<String> problem = PlaceSpot.problem(c.level(), spot, companion);
        if (problem.isPresent()) {
            summary = problem.get();
            return TaskStatus.FAILED;
        }
        Optional<PlaceSpot.Spot> current = PlaceSpot.spotAt(c.level(), spot);
        if (current.isEmpty()) {
            summary = "nothing to place against at " + coords(spot);
            return TaskStatus.FAILED;
        }
        against = current.get().against();
        face = current.get().face();
        if (!companion.hands().canInteract(spot)) {
            summary = coords(spot) + " is " + ProtectionText.describe(c, spot);
            return TaskStatus.FAILED;
        }
        if (!companion.placement().ready()) {
            return TaskStatus.RUNNING;
        }
        ItemStack held = takeOne(c);
        CreativeItemLease lease;
        if (held.isEmpty()) {
            if (!c.canUseCreativeAction(CreativeAction.FREE_MATERIALS)) {
                summary = "I don't have " + itemId();
                return TaskStatus.FAILED;
            }
            lease = CreativeItemLease.synthetic(item.getDefaultInstance());
        } else {
            lease = CreativeItemLease.carried(held);
        }
        lease.place(companion.hands(), against, face);
        if (c.level().getBlockState(spot).getBlock().asItem() == item) {
            placedAt = spot;
            summary = "placed " + itemId() + " at " + coords(spot);
            return TaskStatus.SUCCESS;
        }
        summary = "couldn't place " + itemId() + " at " + coords(spot);
        return TaskStatus.FAILED;
    }

    @Override
    public void stop(TaskContext c) {
        if (approach != null) {
            approach.stop(c);
        }
    }

    @Override
    public String describe() {
        return target == null ? "placing " + itemId() + " nearby" : "placing " + itemId() + " at " + coords(target);
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    private boolean hasItem(dev.friendai.entity.CompanionEntity companion) {
        if (InventoryOps.count(companion.inventory(), matcher) > 0) {
            return true;
        }
        return matcher.test(companion.getMainHandItem());
    }

    private ItemStack takeOne(TaskContext c) {
        List<ItemStack> removed = InventoryOps.remove(c.companion().inventory(), matcher, 1);
        if (!removed.isEmpty()) {
            return removed.get(0);
        }
        if (matcher.test(c.companion().getMainHandItem())) {
            return c.companion().getMainHandItem().split(1);
        }
        return ItemStack.EMPTY;
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
