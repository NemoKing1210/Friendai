package dev.friendai.features.inventory.minecraft.task;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.TaskWorkResult;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import dev.friendai.world.movement.MoveHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Walks to the owner and gives an exact share while leaving any requested reserve with the companion. */
public final class GiveItemsTask implements Task {
    private static final double GIVE_DISTANCE = 2.5;
    private static final EquipmentSlot[] HANDS = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};
    /** How far away a stack the companion put down itself may lie and still count as carried. */
    private static final double OWN_RADIUS = 4.0;
    /** At most this many items are taken off the ground in one delivery. */
    private static final int MAX_ABSORB = 64;

    private static final int MAX_REPORTED_ITEM_IDS = 8;
    private static final int APPROACH_WAIT_LIMIT_TICKS = 200;
    private static final int APPROACH_RETRY_INTERVAL_TICKS = 20;
    private static final int ROUTE_PROBE_MAX_STEPS = 32;

    private final ItemMatcher matcher;
    private final int count;
    private final int retain;
    private final boolean strict;
    private final MoveHelper mover;
    private final BlockPos.MutableBlockPos routeProbePosition = new BlockPos.MutableBlockPos();
    private final Map<String, Integer> handedOverById = new TreeMap<>();
    private CompanionEntity companion;
    private String summary = "";
    /** Whether the ground around the companion has already been searched since it reached the owner. */
    private boolean searchedGround;

    private int pickedUp;
    private boolean waitingForApproach;
    private int approachWaitTicks;
    private boolean lastOwnerAvailable;
    private boolean lastOwnerPositionKnown;
    private double lastOwnerX;
    private double lastOwnerY;
    private double lastOwnerZ;
    private boolean lastRouteProbeKnown;
    private long lastRouteProbe;
    private String waitBlocker = "can't reach the owner";
    private boolean started;
    private String reasonCode = "blocked";
    private String blocker = "";
    private String nextAction = "resolve the delivery blocker and retry without spending the retained share";
    private TaskWorkResult workResult;

    public GiveItemsTask(ItemMatcher matcher, int count) {
        this(matcher, count, 0, false);
    }

    public GiveItemsTask(ItemMatcher matcher, int count, int retain, boolean strict) {
        if (matcher == null) throw new IllegalArgumentException("an item matcher is required");
        if (count < 1 || count > 64) throw new IllegalArgumentException("count must be between 1 and 64");
        if (retain < 0 || retain > 64) throw new IllegalArgumentException("retain must be between 0 and 64");
        this.matcher = matcher;
        this.count = count;
        this.retain = retain;
        this.strict = strict;
        // A strict delivery or a reserved share cannot spend its stock to bridge or pillar on the way to the owner.
        this.mover = new MoveHelper(1.0, 2.0, !strict && retain == 0);
    }

    private String itemLabel() {
        return matcher.label();
    }

    @Override
    public String type() {
        return "give_items";
    }

    @Override
    public void start(TaskContext c) {
        companion = c.companion();
        mover.reset(c);
        searchedGround = false;
        pickedUp = 0;
        waitingForApproach = false;
        approachWaitTicks = 0;
        lastOwnerAvailable = false;
        lastOwnerPositionKnown = false;
        lastRouteProbeKnown = false;
        waitBlocker = "can't reach the owner";
        handedOverById.clear();
        summary = "";
        started = true;
        reasonCode = "blocked";
        blocker = "";
        nextAction = "resolve the delivery blocker and retry without spending the retained share";
        workResult = null;
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        CompanionEntity companion = c.companion();
        int have = carried(companion);
        if (have == 0) {
            pickedUp += absorbNearby(companion);
            have = carried(companion);
        }
        if (have == 0) return fail(c, "I don't have " + itemLabel());

        ServerPlayer owner = c.owner().orElse(null);
        if (waitingForApproach) return waitForApproach(c, owner);
        if (!ownerAvailable(c, owner)) {
            waitBlocker = "the owner is not around";
            beginApproachWait(c, owner);
            return TaskStatus.RUNNING;
        }

        boolean close = companion.distanceTo(owner) <= GIVE_DISTANCE;
        MoveHelper.State state = close ? MoveHelper.State.ARRIVED : mover.tick(c, owner.position());
        if (state == MoveHelper.State.STUCK) {
            waitBlocker = "can't reach the owner";
            beginApproachWait(c, owner);
            return TaskStatus.RUNNING;
        }
        if (state != MoveHelper.State.ARRIVED && !close) return TaskStatus.RUNNING;
        return handOver(c, owner);
    }

    private TaskStatus waitForApproach(TaskContext c, ServerPlayer owner) {
        approachWaitTicks++;
        if (approachWaitTicks % APPROACH_RETRY_INTERVAL_TICKS == 0) {
            boolean available = ownerAvailable(c, owner);
            boolean moved = ownerMoved(owner, available);
            long routeProbe = available ? routeProbe(c, owner) : 0L;
            boolean routeChanged = available && lastRouteProbeKnown && routeProbe != lastRouteProbe;
            boolean availabilityChanged = available != lastOwnerAvailable;
            rememberApproachState(owner, available, routeProbe);
            if (available && (availabilityChanged || moved || routeChanged)) {
                waitingForApproach = false;
                mover.reset(c);
                CompanionEntity companion = c.companion();
                boolean close = companion.distanceTo(owner) <= GIVE_DISTANCE;
                MoveHelper.State state = close ? MoveHelper.State.ARRIVED : mover.tick(c, owner.position());
                if (state == MoveHelper.State.STUCK) {
                    waitBlocker = "can't reach the owner";
                    beginApproachWait(c, owner);
                } else if (state == MoveHelper.State.ARRIVED || close) {
                    return handOver(c, owner);
                }
                if (approachWaitTicks >= APPROACH_WAIT_LIMIT_TICKS && waitingForApproach) {
                    return fail(c, waitBlocker);
                }
                return TaskStatus.RUNNING;
            }
        }
        if (approachWaitTicks >= APPROACH_WAIT_LIMIT_TICKS) return fail(c, waitBlocker);
        return TaskStatus.RUNNING;
    }

    private void beginApproachWait(TaskContext c, ServerPlayer owner) {
        waitingForApproach = true;
        mover.stop(c);
        boolean available = ownerAvailable(c, owner);
        rememberApproachState(owner, available, available ? routeProbe(c, owner) : 0L);
    }

    private boolean ownerMoved(ServerPlayer owner, boolean available) {
        if (!available) return false;
        return !lastOwnerPositionKnown
                || owner.getX() != lastOwnerX
                || owner.getY() != lastOwnerY
                || owner.getZ() != lastOwnerZ;
    }

    private void rememberApproachState(ServerPlayer owner, boolean available, long routeProbe) {
        lastOwnerAvailable = available;
        lastOwnerPositionKnown = available;
        if (available) {
            lastOwnerX = owner.getX();
            lastOwnerY = owner.getY();
            lastOwnerZ = owner.getZ();
            lastRouteProbe = routeProbe;
            lastRouteProbeKnown = true;
        } else {
            lastRouteProbe = 0L;
            lastRouteProbeKnown = false;
        }
    }

    private boolean ownerAvailable(TaskContext c, ServerPlayer owner) {
        return owner != null && owner.level() == c.level();
    }

    /** Hashes a bounded, loaded-only corridor around the direct route; it never loads a chunk. */
    private long routeProbe(TaskContext c, ServerPlayer owner) {
        CompanionEntity companion = c.companion();
        double dx = owner.getX() - companion.getX();
        double dy = owner.getY() - companion.getY();
        double dz = owner.getZ() - companion.getZ();
        double horizontal = Math.hypot(dx, dz);
        int steps = Math.max(1, Math.min(ROUTE_PROBE_MAX_STEPS, (int) Math.ceil(Math.max(horizontal, Math.abs(dy)))));
        double lateralX = horizontal > 0.0 ? -dz / horizontal : 0.0;
        double lateralZ = horizontal > 0.0 ? dx / horizontal : 0.0;
        long hash = 0xcbf29ce484222325L;
        for (int step = 0; step <= steps; step++) {
            double fraction = step / (double) steps;
            double x = companion.getX() + dx * fraction;
            double y = companion.getY() + dy * fraction;
            double z = companion.getZ() + dz * fraction;
            for (int side = -1; side <= 1; side++) {
                int sampleX = (int) Math.floor(x + lateralX * side);
                int sampleZ = (int) Math.floor(z + lateralZ * side);
                int sampleY = (int) Math.floor(y);
                for (int height = 0; height < 2; height++) {
                    routeProbePosition.set(sampleX, sampleY + height, sampleZ);
                    if (!c.level().hasChunkAt(routeProbePosition)) {
                        hash = mix(hash, 0x7f4a7c15);
                    } else {
                        BlockState state = c.level().getBlockState(routeProbePosition);
                        hash = mix(hash, state.hashCode());
                    }
                }
            }
        }
        return hash;
    }

    private static long mix(long hash, int value) {
        return (hash ^ value) * 0x100000001b3L;
    }

    private TaskStatus handOver(TaskContext c, ServerPlayer owner) {
        CompanionEntity companion = c.companion();
        companion.getNavigation().stop();
        if (!searchedGround) {
            searchedGround = true;
            if (carried(companion) < count + retain) pickedUp += absorbNearby(companion);
        }

        // This is the final stock check before any inventory mutation or drop, on the owning server thread.
        int available = carried(companion);
        if (strict && available < count + retain) {
            return fail(
                    c,
                    "need " + (count + retain) + " " + itemLabel() + " to give " + count + " and keep " + retain
                            + "; have " + available);
        }
        int wanted = Math.min(count, Math.max(0, available - retain));
        if (wanted == 0) {
            return fail(c, "not enough " + itemLabel() + " to give " + count + " and keep " + retain);
        }

        int bag = InventoryOps.count(companion.inventory(), matcher);
        int inMain = countOf(companion.getMainHandItem());
        int inOff = countOf(companion.getOffhandItem());
        List<ItemStack> taken = new ArrayList<>(InventoryOps.remove(companion.inventory(), matcher, wanted));
        int removedFromBag = taken.stream().mapToInt(ItemStack::getCount).sum();
        int remaining = wanted - removedFromBag;
        for (EquipmentSlot slot : HANDS) {
            if (remaining <= 0) break;
            ItemStack stack = hand(slot, companion);
            if (!matcher.test(stack)) continue;
            int amount = Math.min(remaining, stack.getCount());
            ItemStack copy = stack.copyWithCount(amount);
            stack.shrink(amount);
            companion.setItemSlot(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
            taken.add(copy);
            remaining -= amount;
        }
        int removed = taken.stream().mapToInt(ItemStack::getCount).sum();
        if (removed != wanted) {
            restore(companion, taken);
            return fail(c, "could only reserve " + removed + " of " + wanted + " " + itemLabel() + " for handover");
        }

        List<ItemEntity> drops = new ArrayList<>(taken.size());
        for (ItemStack stack : taken) {
            ItemEntity drop = drop(c, owner, stack);
            if (drop == null) {
                drops.forEach(ItemEntity::discard);
                restore(companion, taken);
                return fail(c, "could not create the owner handover for " + itemLabel());
            }
            drops.add(drop);
        }

        int gave = 0;
        for (int i = 0; i < taken.size(); i++) {
            ItemStack stack = taken.get(i);
            ItemEntity drop = drops.get(i);
            gave += stack.getCount();
            String itemId = itemId(stack);
            handedOverById.merge(itemId, stack.getCount(), Integer::sum);
            companion.recordDelivery(new dev.friendai.features.inventory.DeliveredLedger.Entry(
                    itemId, stack.getCount(), c.level().getGameTime(), drop.getUUID()));
        }
        int carriedAfter = carried(companion);
        summary = "gave " + gave + " " + itemLabel();
        if (retain > 0) summary += " and kept " + Math.min(retain, carriedAfter) + " " + itemLabel();
        boolean complete = gave >= count;
        reasonCode = complete ? "complete" : "partial";
        blocker = complete ? "" : "only gave " + gave + " of " + count + " " + itemLabel();
        nextAction = complete
                ? "continue with the next order step"
                : "retry the remaining share when more matching items are available";
        workResult = createWorkResult(companion);
        emitEvidence(c, available, bag, inMain, inOff, pickedUp, gave, carriedAfter);
        return TaskStatus.SUCCESS;
    }

    private TaskStatus fail(TaskContext c, String message) {
        mover.stop(c);
        c.companion().getNavigation().stop();
        summary = message;
        reasonCode = handedOverById.isEmpty() ? "blocked" : "partial";
        blocker = message;
        nextAction = "resolve the delivery blocker and retry without spending the retained share";
        workResult = createWorkResult(c.companion());
        return TaskStatus.FAILED;
    }

    private void restore(CompanionEntity companion, List<ItemStack> taken) {
        for (ItemStack stack : taken) InventoryOps.insertOrDrop(companion, stack);
    }

    /** Everything the companion could hand over right now: the bag plus both hands. */
    private int carried(CompanionEntity companion) {
        return InventoryOps.count(companion.inventory(), matcher)
                + countOf(companion.getMainHandItem())
                + countOf(companion.getOffhandItem());
    }

    private int countOf(ItemStack stack) {
        return !stack.isEmpty() && matcher.test(stack) ? stack.getCount() : 0;
    }

    private Map<String, Integer> carriedByItemId(CompanionEntity companion) {
        Map<String, Integer> counts = new TreeMap<>();
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            ItemStack stack = companion.inventory().getItem(slot);
            addCarried(counts, stack);
        }
        addCarried(counts, companion.getMainHandItem());
        addCarried(counts, companion.getOffhandItem());
        return counts;
    }

    private void addCarried(Map<String, Integer> counts, ItemStack stack) {
        if (!stack.isEmpty() && matcher.test(stack)) counts.merge(itemId(stack), stack.getCount(), Integer::sum);
    }

    private TaskWorkResult createWorkResult(CompanionEntity companion) {
        Map<String, Integer> carried = carriedByItemId(companion);
        TreeSet<String> itemIds = new TreeSet<>(handedOverById.keySet());
        itemIds.addAll(carried.keySet());
        boolean exactItem = !matcher.label().startsWith("#");
        if (exactItem) itemIds.add(matcher.label());
        boolean capped = itemIds.size() > MAX_REPORTED_ITEM_IDS;
        List<String> reportedIds = itemIds.stream().limit(MAX_REPORTED_ITEM_IDS).toList();
        List<TaskWorkResult.Line> lines = new ArrayList<>(reportedIds.size() * 2);
        int handedOver =
                handedOverById.values().stream().mapToInt(Integer::intValue).sum();
        for (String itemId : reportedIds) {
            int handedCount = handedOverById.getOrDefault(itemId, 0);
            if (handedCount > 0 || exactItem) {
                lines.add(new TaskWorkResult.Line(
                        itemId,
                        exactItem ? count : Math.max(1, handedCount),
                        handedCount,
                        TaskWorkResult.Evidence.HANDED_OVER));
            }
            int carriedCount = carried.getOrDefault(itemId, 0);
            int carriedRequested =
                    exactItem ? retain > 0 ? retain : Math.max(1, count - handedOver) : Math.max(1, carriedCount);
            if (carriedCount > 0 || exactItem && retain > 0) {
                lines.add(new TaskWorkResult.Line(
                        itemId, carriedRequested, carriedCount, TaskWorkResult.Evidence.CARRIED));
            }
        }
        String resultBlocker = capped
                ? append(blocker, "measured item details are capped at " + MAX_REPORTED_ITEM_IDS + " item types")
                : blocker;
        String resultReason = capped && reasonCode.equals("complete") ? "details_limited" : reasonCode;
        return new TaskWorkResult(lines, resultReason, resultBlocker, nextAction);
    }

    private static String append(String first, String second) {
        return first == null || first.isBlank() ? second : first + "; " + second;
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** The hand's stack, read the way the rest of the codebase reads a hand rather than through the slot map. */
    private static ItemStack hand(EquipmentSlot slot, CompanionEntity companion) {
        return slot == EquipmentSlot.MAINHAND ? companion.getMainHandItem() : companion.getOffhandItem();
    }

    /**
     * Takes matching stacks lying within {@link #OWN_RADIUS} blocks into the bag: a tool the companion swapped out
     * with a full bag was dropped at its feet, and a delivery owns what it put down. Bounded by {@link #MAX_ABSORB}.
     */
    private int absorbNearby(CompanionEntity companion) {
        List<ItemEntity> lying = companion
                .level()
                .getEntitiesOfClass(
                        ItemEntity.class,
                        companion.getBoundingBox().inflate(OWN_RADIUS),
                        entity -> entity.isAlive() && !entity.isRemoved() && matcher.test(entity.getItem()));
        int absorbed = 0;
        for (ItemEntity entity : lying) {
            if (absorbed >= MAX_ABSORB) break;
            ItemStack stack = entity.getItem();
            int amount = Math.min(stack.getCount(), MAX_ABSORB - absorbed);
            ItemStack moving = stack.copyWithCount(amount);
            stack.shrink(amount);
            if (stack.isEmpty()) {
                entity.discard();
            } else {
                entity.setItem(stack);
            }
            InventoryOps.insertOrDrop(companion, moving);
            absorbed += amount;
        }
        return absorbed;
    }

    /** Bounded, already-observed evidence; a delivered item is not proof the owner picked it up. */
    private void emitEvidence(
            TaskContext c,
            int availableBefore,
            int bag,
            int inMain,
            int inOff,
            int pickedUp,
            int gave,
            int carriedAfter) {
        var companion = c.companion();
        java.util.Map<String, String> facts = new java.util.LinkedHashMap<>();
        facts.put("requested", Integer.toString(count));
        facts.put("retain", Integer.toString(retain));
        facts.put("strict", Boolean.toString(strict));
        facts.put("available", Integer.toString(availableBefore));
        facts.put("inventoryCount", Integer.toString(bag));
        facts.put("heldMain", Integer.toString(inMain));
        facts.put("heldOff", Integer.toString(inOff));
        facts.put("pickedUp", Integer.toString(pickedUp));
        facts.put("removed", Integer.toString(gave));
        facts.put("dropped", Integer.toString(gave));
        facts.put("carriedAfter", Integer.toString(carriedAfter));
        facts.put("ownerReceipt", "unknown");
        c.decision(dev.friendai.foundation.diagnostics.LogEvents.INVENTORY_DELTA, "give_completed", facts);
    }

    private static ItemEntity drop(TaskContext c, ServerPlayer owner, ItemStack stack) {
        CompanionEntity companion = c.companion();
        ItemEntity entity =
                new ItemEntity(c.level(), companion.getX(), companion.getEyeY() - 0.3, companion.getZ(), stack);
        Vec3 toOwner = owner.position()
                .add(0, 1.0, 0)
                .subtract(entity.position())
                .normalize()
                .scale(0.3);
        entity.setDeltaMovement(toOwner);
        entity.setPickUpDelay(10);
        return c.level().addFreshEntity(entity) ? entity : null;
    }

    @Override
    public void stop(TaskContext c) {
        waitingForApproach = false;
        mover.stop(c);
        c.companion().getNavigation().stop();
    }

    @Override
    public void onTerminal(TaskContext c) {
        companion = null;
    }

    @Override
    public int waitingTicks() {
        return waitingForApproach ? approachWaitTicks : -1;
    }

    @Override
    public int waitingLimitTicks() {
        return waitingForApproach ? APPROACH_WAIT_LIMIT_TICKS : 0;
    }

    @Override
    public String debugStatus() {
        return waitingForApproach
                ? "waiting for owner route " + approachWaitTicks + "/" + APPROACH_WAIT_LIMIT_TICKS + ": " + waitBlocker
                : mover.debugStatus();
    }

    @Override
    public String describe() {
        return "giving " + count + " " + itemLabel() + " to the owner"
                + (retain > 0 ? " while keeping " + retain + " " + itemLabel() : "");
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    @Override
    public Optional<TaskWorkResult> workResult() {
        if (!started) return Optional.empty();
        if (workResult == null) workResult = createWorkResult(companion);
        return Optional.of(workResult);
    }
}
