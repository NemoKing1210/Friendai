package dev.friendai.features.inventory.minecraft.task;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.interact.ContainerAccess;
import dev.friendai.task.interact.ContainerSession;
import dev.friendai.task.interact.ContainerTransfer;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * Moves one bounded item count from one storage container to another, returning leftovers to the source.
 *
 * <p>The transfer is driven by the stacks it withdrew, not by the matcher: with a tag such as
 * {@code #minecraft:planks} the withdrawn birch is deposited while the companion's own oak stays carried, and the
 * same received stacks are what can go back to the source. The summary always accounts for the requested,
 * withdrawn, delivered, returned, carried and vanished quantities.
 */
public final class MoveItemsTask implements Task {
    enum Phase {
        TAKE,
        STORE,
        RETURN,
        DONE
    }

    private final ItemMatcher matcher;
    private final int count;
    private final BlockPos from;
    private final BlockPos to;

    private ContainerSession session;
    private Phase phase = Phase.TAKE;
    private int moved;
    private int inserted;
    private int returned;
    private int leftover;
    private String failure;
    private String summary = "";
    private boolean finished;
    /** What the withdrawal actually took, one entry per container stack; empty for a legacy checkpoint. */
    private List<ItemStack> receipts = List.of();
    /** How much of each receipt the destination accepted so far. */
    private int[] delivered = new int[0];
    /** Registry access for the checkpoint codec; captured on the first tick and never serialized. */
    private HolderLookup.Provider registries;

    public MoveItemsTask(ItemMatcher matcher, int count, BlockPos from, BlockPos to) {
        if (matcher == null) throw new IllegalArgumentException("item is required");
        if (count < 1) throw new IllegalArgumentException("count must be positive");
        this.matcher = matcher;
        this.count = count;
        this.from = from;
        this.to = to;
    }

    /** Rebuilds a transfer whose withdrawal is already committed, so a restart stores it instead of taking again. */
    private MoveItemsTask(
            ItemMatcher matcher,
            int count,
            BlockPos from,
            BlockPos to,
            Phase phase,
            int moved,
            int inserted,
            int returned,
            int leftover,
            List<ItemStack> receipts,
            int[] delivered) {
        this(matcher, count, from, to);
        this.phase = phase;
        this.moved = moved;
        this.inserted = inserted;
        this.returned = returned;
        this.leftover = leftover;
        List<ItemStack> copy = new ArrayList<>();
        for (ItemStack stack : receipts == null ? List.<ItemStack>of() : receipts) {
            if (stack != null && !stack.isEmpty()) copy.add(stack.copy());
        }
        this.receipts = List.copyOf(copy);
        this.delivered = delivered != null && delivered.length == this.receipts.size()
                ? delivered.clone()
                : new int[this.receipts.size()];
    }

    static MoveItemsTask resumed(
            ItemMatcher matcher,
            int count,
            BlockPos from,
            BlockPos to,
            Phase phase,
            int moved,
            int inserted,
            int returned,
            int leftover,
            List<ItemStack> receipts,
            int[] delivered) {
        return new MoveItemsTask(
                matcher, count, from, to, phase, moved, inserted, returned, leftover, receipts, delivered);
    }

    @Override
    public String type() {
        return "move_items";
    }

    @Override
    public void start(TaskContext c) {
        captureRegistries(c);
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        if (finished) return TaskStatus.SUCCESS;
        if (from == null || to == null) {
            finished = true;
            summary = "give both container positions";
            return TaskStatus.FAILED;
        }
        if (from.equals(to)) {
            finished = true;
            summary = "the source and the target are the same container";
            return TaskStatus.FAILED;
        }
        if (phase == Phase.DONE) return finish();
        if (session == null) {
            session = new ContainerSession(
                    target(),
                    null,
                    false,
                    () -> "no storage container for move_items",
                    position -> "there is no storage container at " + coords(position));
        }
        TaskStatus status = session.tick(c, this::work);
        if (status == TaskStatus.RUNNING) return TaskStatus.RUNNING;
        String sessionFailure = session.failureSummary();
        session = null;
        if (status == TaskStatus.FAILED) {
            finished = true;
            BlockPos attempted = target();
            if (attempted != null && ContainerAccess.at(c.level(), attempted).isEmpty()) {
                c.storage().forget(c.companion(), c.level(), attempted);
            }
            String reason = sessionFailure == null || sessionFailure.isBlank() ? summary : sessionFailure;
            if (moved > 0) {
                reason += "; " + moved + " " + matcher.label() + " are still in my inventory";
            }
            summary = reason;
            return TaskStatus.FAILED;
        }
        switch (phase) {
            case TAKE -> phase = Phase.STORE;
            case STORE -> phase = leftover > 0 ? Phase.RETURN : Phase.DONE;
            case RETURN -> phase = Phase.DONE;
            case DONE -> {
                return finish();
            }
        }
        return phase == Phase.DONE ? finish() : TaskStatus.RUNNING;
    }

    @Override
    public void stop(TaskContext c) {
        if (session != null) {
            session.stop(c);
            session = null;
        }
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    /**
     * Durable progress only after the withdrawal committed: before TAKE the request can be repeated from intent
     * without touching the world, while STORE and RETURN already hold items that a fresh TAKE would withdraw again.
     */
    @Override
    public String saveData() {
        if (phase != Phase.STORE && phase != Phase.RETURN) return null;
        return MoveItemsCheckpoints.encode(
                matcher, count, from, to, phase, moved, inserted, returned, leftover, receipts, delivered, registries);
    }

    @Override
    public String describe() {
        return "moving " + count + " " + matcher.label() + " between containers";
    }

    private TaskStatus work(TaskContext c, BlockPos position, Container container) {
        captureRegistries(c);
        return switch (phase) {
            case TAKE -> {
                ContainerTransfer.Result result = ContainerTransfer.take(c, position, container, matcher, count);
                moved = result.moved();
                receipts = result.withdrawn();
                delivered = new int[receipts.size()];
                summary = result.summary();
                ContainerAccess.recordContents(c, position, container);
                yield result.status();
            }
            case STORE -> {
                inserted += store(c.companion(), container);
                leftover = carried(c.companion());
                ContainerAccess.recordContents(c, position, container);
                if (inserted == 0 && leftover > 0) {
                    failure = "the "
                            + ContainerAccess.shortLabel(c.level(), position)
                            + " at "
                            + coords(position)
                            + " is full";
                }
                yield TaskStatus.SUCCESS;
            }
            case RETURN -> {
                returned += giveBack(c.companion(), container);
                leftover = carried(c.companion());
                ContainerAccess.recordContents(c, position, container);
                yield TaskStatus.SUCCESS;
            }
            case DONE -> TaskStatus.SUCCESS;
        };
    }

    /**
     * Deposits the withdrawn stacks and only them, in receipt order: a carried stack of another item or another set of
     * components is never taken for the withdrawal. A legacy checkpoint has no receipts and keeps the old matcher
     * behavior for its remaining count.
     */
    private int store(CompanionEntity companion, Container target) {
        if (receipts.isEmpty()) return insertMatching(companion, target, moved - inserted);
        int stored = 0;
        boolean full = false;
        for (int i = 0; i < receipts.size() && !full; i++) {
            ItemStack receipt = receipts.get(i);
            int remaining = receipt.getCount() - delivered[i];
            if (remaining <= 0) continue;
            for (int slot = 0; slot < companion.inventory().getContainerSize() && remaining > 0 && !full; slot++) {
                ItemStack stack = companion.inventory().getItem(slot);
                if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, receipt)) continue;
                int take = Math.min(remaining, stack.getCount());
                ItemStack rejected = ContainerAccess.insert(target, stack.copyWithCount(take));
                int accepted = take - rejected.getCount();
                if (accepted > 0) {
                    stack.shrink(accepted);
                    if (stack.isEmpty()) companion.inventory().setItem(slot, ItemStack.EMPTY);
                    delivered[i] += accepted;
                    stored += accepted;
                    remaining -= accepted;
                }
                if (!rejected.isEmpty()) full = true;
            }
        }
        companion.inventory().setChanged();
        target.setChanged();
        return stored;
    }

    /** Puts the un-stored remainder of each receipt back into the source, and only that remainder. */
    private int giveBack(CompanionEntity companion, Container source) {
        if (receipts.isEmpty()) return insertMatching(companion, source, leftover);
        int sent = 0;
        boolean full = false;
        for (int i = 0; i < receipts.size() && !full; i++) {
            ItemStack receipt = receipts.get(i);
            int remaining = receipt.getCount() - delivered[i];
            if (remaining <= 0) continue;
            for (int slot = 0; slot < companion.inventory().getContainerSize() && remaining > 0 && !full; slot++) {
                ItemStack stack = companion.inventory().getItem(slot);
                if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, receipt)) continue;
                int take = Math.min(remaining, stack.getCount());
                ItemStack rejected = ContainerAccess.insert(source, stack.copyWithCount(take));
                int accepted = take - rejected.getCount();
                if (accepted > 0) {
                    stack.shrink(accepted);
                    if (stack.isEmpty()) companion.inventory().setItem(slot, ItemStack.EMPTY);
                    sent += accepted;
                    remaining -= accepted;
                }
                if (!rejected.isEmpty()) full = true;
            }
        }
        companion.inventory().setChanged();
        source.setChanged();
        return sent;
    }

    /** How many un-delivered withdrawn items are really still carried. */
    private int carried(CompanionEntity companion) {
        if (receipts.isEmpty()) return Math.max(0, moved - inserted - returned);
        int carried = 0;
        for (int i = 0; i < receipts.size(); i++) {
            int remaining = receipts.get(i).getCount() - delivered[i];
            if (remaining > 0) carried += Math.min(remaining, countCarried(companion, receipts.get(i)));
        }
        return carried;
    }

    private static int countCarried(CompanionEntity companion, ItemStack receipt) {
        int total = 0;
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            ItemStack stack = companion.inventory().getItem(slot);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, receipt)) total += stack.getCount();
        }
        return total;
    }

    /** The legacy path for a checkpoint written before receipts existed. */
    private int insertMatching(CompanionEntity companion, Container container, int limit) {
        int inserted = 0;
        for (int slot = 0; slot < companion.inventory().getContainerSize() && inserted < limit; slot++) {
            ItemStack stack = companion.inventory().getItem(slot);
            if (stack.isEmpty() || !matcher.test(stack)) {
                continue;
            }
            int take = Math.min(limit - inserted, stack.getCount());
            ItemStack leftoverStack = ContainerAccess.insert(container, stack.copyWithCount(take));
            int stored = take - leftoverStack.getCount();
            if (stored > 0) {
                stack.shrink(stored);
                if (stack.isEmpty()) {
                    companion.inventory().setItem(slot, ItemStack.EMPTY);
                }
                inserted += stored;
            }
            if (!leftoverStack.isEmpty()) {
                break;
            }
        }
        companion.inventory().setChanged();
        container.setChanged();
        return inserted;
    }

    private TaskStatus finish() {
        finished = true;
        int missing = Math.max(0, moved - inserted - returned - leftover);
        String label = matcher.label();
        if (inserted == moved) {
            summary = "moved " + inserted + " " + label + " from the container at " + coords(from)
                    + " to the container at " + coords(to);
            if (moved < count) summary += "; the source had only " + moved + " of " + count;
            return TaskStatus.SUCCESS;
        }
        StringBuilder text = new StringBuilder();
        if (inserted == 0 && failure != null) {
            text.append(failure);
        } else {
            text.append("only ")
                    .append(inserted)
                    .append(" of ")
                    .append(count)
                    .append(' ')
                    .append(label)
                    .append(" reached the container at ")
                    .append(coords(to))
                    .append(" from the container at ")
                    .append(coords(from));
        }
        if (moved < count) text.append("; the source had only ").append(moved);
        if (returned > 0) text.append("; ").append(returned).append(" went back to the source");
        if (leftover > 0) text.append("; ").append(leftover).append(" are still in my inventory");
        if (missing > 0) text.append("; ").append(missing).append(" are no longer in my inventory");
        summary = text.toString();
        return TaskStatus.FAILED;
    }

    private void captureRegistries(TaskContext c) {
        if (c != null && c.level() != null && c.level().registryAccess() != null) {
            registries = c.level().registryAccess();
        }
    }

    private BlockPos target() {
        return switch (phase) {
            case TAKE, RETURN -> from;
            case STORE, DONE -> to;
        };
    }

    private static String coords(BlockPos position) {
        return position.getX() + " " + position.getY() + " " + position.getZ();
    }
}
