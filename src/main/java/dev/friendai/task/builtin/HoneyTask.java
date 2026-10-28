package dev.friendai.task.builtin;

import dev.friendai.task.Task;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.TaskContext;
import dev.friendai.task.autonomy.ProtectionText;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.inventory.DropSettle;
import dev.friendai.task.inventory.ItemMatcher;
import dev.friendai.task.nav.Approach;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

/** Collects honey from a smoked, full beehive with a bottle or shears. */
public final class HoneyTask implements Task {
    private static final int RADIUS = 16;
    /** How long the honey gets to land and leave its pickup delay before the task moves on. */
    private static final int SETTLE_TICKS = 12;
    private static final ItemMatcher HONEY_BOTTLES = ItemMatcher.parse("minecraft:honey_bottle").orElseThrow();
    private static final ItemMatcher HONEYCOMBS = ItemMatcher.parse("minecraft:honeycomb").orElseThrow();

    private final boolean bottle;
    private final @Nullable BlockPos hive;

    private boolean preflightDone;
    private boolean failed;
    private boolean finished;
    private String summary = "";
    private BlockPos target;
    private Approach approach;
    /** The wait for the honey to land, and what the companion carried when it took from the hive. */
    private DropSettle settle;
    private int before;

    public HoneyTask(boolean bottle, @Nullable BlockPos hive) {
        this.bottle = bottle;
        this.hive = hive;
    }

    @Override
    public String type() {
        return "harvest_honey";
    }

    @Override
    public void start(TaskContext c) {}

    @Override
    public TaskStatus tick(TaskContext c) {
        if (failed) {
            return TaskStatus.FAILED;
        }
        if (finished) {
            return TaskStatus.SUCCESS;
        }
        if (settle != null) {
            if (!settle.tick(c)) {
                return TaskStatus.RUNNING;
            }
            settle = null;
            int gained = c.companion().inventory().countItem(bottle ? Items.HONEY_BOTTLE : Items.HONEYCOMB) - before;
            if (gained <= 0) {
                summary = "the hive at " + coords(target) + " didn't give any honey";
                failed = true;
                return TaskStatus.FAILED;
            }
            Item got = bottle ? Items.HONEY_BOTTLE : Items.HONEYCOMB;
            summary = "got " + gained + " " + BuiltInRegistries.ITEM.getKey(got) + " from the hive at " + coords(target);
            finished = true;
            return TaskStatus.SUCCESS;
        }
        if (!preflightDone) {
            preflightDone = true;
            Item tool = bottle ? Items.GLASS_BOTTLE : Items.SHEARS;
            if (!carried(c.companion(), tool)) {
                summary = "I have no " + BuiltInRegistries.ITEM.getKey(tool);
                failed = true;
                return TaskStatus.FAILED;
            }
            Optional<BlockPos> found = hive != null ? Optional.of(hive) : nearestHive(c.companion());
            if (found.isEmpty()) {
                summary = "no beehive nearby";
                failed = true;
                return TaskStatus.FAILED;
            }
            target = found.get();
            if (!(c.level().getBlockState(target).getBlock() instanceof BeehiveBlock)) {
                summary = "there is no beehive at " + coords(target);
                failed = true;
                return TaskStatus.FAILED;
            }
            if (c.level().getBlockState(target).getValue(BeehiveBlock.HONEY_LEVEL) < BeehiveBlock.MAX_HONEY_LEVELS) {
                summary = "the hive at " + coords(target) + " isn't full yet";
                failed = true;
                return TaskStatus.FAILED;
            }
            if (!CampfireBlock.isSmokeyPos(c.level(), target) && !beesEmpty(c.level(), target)) {
                summary = "the hive at " + coords(target) + " isn't smoked; place a campfire under it";
                failed = true;
                return TaskStatus.FAILED;
            }
        }
        if (approach == null) {
            approach = new Approach(target);
        }
        Approach.State state = approach.tick(c.companion());
        if (state == Approach.State.MOVING) {
            return TaskStatus.RUNNING;
        }
        if (state == Approach.State.UNREACHABLE) {
            summary = "can't reach the hive at " + coords(target);
            failed = true;
            return TaskStatus.FAILED;
        }
        if (!c.companion().hands().canInteract(target)) {
            summary = "the hive at " + coords(target) + " is " + ProtectionText.describe(c, target);
            failed = true;
            return TaskStatus.FAILED;
        }
        Item tool = bottle ? Items.GLASS_BOTTLE : Items.SHEARS;
        ItemStack stack = takeOne(c.companion(), tool);
        if (stack.isEmpty()) {
            summary = "I have no " + BuiltInRegistries.ITEM.getKey(tool);
            failed = true;
            return TaskStatus.FAILED;
        }
        before = c.companion().inventory().countItem(bottle ? Items.HONEY_BOTTLE : Items.HONEYCOMB);
        c.companion().hands().place(stack, target, Direction.UP);
        settle = new DropSettle(SETTLE_TICKS, DropSettle.INTERACT_RADIUS, bottle ? HONEY_BOTTLES : HONEYCOMBS);
        return TaskStatus.RUNNING;
    }

    @Override
    public void stop(TaskContext c) {
        settle = null;
        c.companion().getNavigation().stop();
    }

    @Override
    public String describe() {
        return "harvesting honey";
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    private static boolean carried(dev.friendai.entity.CompanionEntity companion, Item item) {
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            if (companion.inventory().getItem(slot).is(item)) {
                return true;
            }
        }
        return false;
    }

    private static ItemStack takeOne(dev.friendai.entity.CompanionEntity companion, Item item) {
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            ItemStack stack = companion.inventory().getItem(slot);
            if (stack.is(item)) {
                ItemStack one = stack.copyWithCount(1);
                stack.shrink(1);
                if (stack.isEmpty()) {
                    companion.inventory().setItem(slot, ItemStack.EMPTY);
                }
                companion.inventory().setChanged();
                return one;
            }
        }
        return ItemStack.EMPTY;
    }

    private static Optional<BlockPos> nearestHive(CompanionEntity companion) {
        if (!(companion.level() instanceof ServerLevel level)) return Optional.empty();
        var matcher = dev.friendai.task.mining.BlockMatcher.parse("#minecraft:beehives").orElse(null);
        if (matcher == null) return Optional.empty();
        return dev.friendai.core.FriendaiServerState.get(level.getServer()).resources()
                .visible(companion, matcher, RADIUS, 1).stream().findFirst();
    }

    private static boolean beesEmpty(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof BeehiveBlockEntity beehive && beehive.isEmpty();
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
