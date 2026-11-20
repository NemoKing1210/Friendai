package dev.friendai.features.animals.minecraft.task;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.animals.minecraft.Animals;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.autonomy.ProtectionText;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.world.movement.Approach;
import dev.friendai.world.movement.MoveHelper;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Leads animals to a place, then ties them to a fence or lets them go. */
public final class LeadTask implements Task {
    private static final int RADIUS = 16;
    private static final int FENCE_RADIUS = 3;
    private static final int WAIT_LIMIT = 100;
    private static final double BEHIND_DISTANCE = 6.0;
    private static final double ARRIVAL_DISTANCE = 2.0;

    private enum Stage {
        ATTACH,
        WALK
    }

    /** The stack already spent on a leash we may still own. */
    private record Attachment(Animal animal, Leashable.LeashData leashData, ItemStack lead) {}

    private final EntityType<?> type;
    private final int count;
    private final BlockPos destination;
    private final Vec3 destinationCenter;
    private final boolean tie;
    private final String id;
    private final MoveHelper mover = new MoveHelper(0.8, 1.5);
    private final List<Animal> queue = new ArrayList<>();
    private final List<Attachment> leashed = new ArrayList<>();
    private Vec3 origin;
    private boolean preflightDone;
    private boolean failed;
    private boolean finished;
    private String summary = "";
    private Stage stage = Stage.ATTACH;
    private Approach approach;
    private int waitTicks;
    private int lost;

    public LeadTask(EntityType<?> type, int count, BlockPos destination, boolean tie) {
        this.type = type;
        this.count = count;
        this.destination = destination;
        this.destinationCenter = Vec3.atCenterOf(destination);
        this.tie = tie;
        this.id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
    }

    @Override
    public String type() {
        return "lead_animals";
    }

    @Override
    public void start(TaskContext c) {
        origin = c.companion().position();
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        if (failed) {
            return TaskStatus.FAILED;
        }
        if (finished) {
            return TaskStatus.SUCCESS;
        }
        if (!preflightDone) {
            preflightDone = true;
            int leads = c.companion().inventory().countItem(Items.LEAD);
            if (leads == 0) {
                summary = "I have no minecraft:lead";
                failed = true;
                return TaskStatus.FAILED;
            }
            List<Animal> nearby =
                    Animals.nearby(c.level(), c.companion(), origin, RADIUS, candidate -> candidate == type);
            nearby.stream()
                    .filter(animal ->
                            ((Leashable) animal).canBeLeashed() && ((Leashable) animal).getLeashData() == null)
                    .limit(Math.min(count, leads))
                    .forEach(queue::add);
            if (queue.isEmpty()) {
                summary = "no " + id + " nearby";
                failed = true;
                return TaskStatus.FAILED;
            }
        }
        return switch (stage) {
            case ATTACH -> attach(c);
            case WALK -> walk(c);
        };
    }

    @Override
    public void stop(TaskContext c) {
        if (approach != null) {
            approach.stop(c);
            approach = null;
        }
        mover.stop(c);
        releaseOwnedLeashes(c);
    }

    @Override
    public void onTerminal(TaskContext c) {
        stop(c);
    }

    @Override
    public String describe() {
        return "leading " + id + " to " + coords(destination);
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    @Override
    public int waitingTicks() {
        return waitTicks == 0 ? -1 : waitTicks;
    }

    @Override
    public int waitingLimitTicks() {
        return WAIT_LIMIT;
    }

    private TaskStatus attach(TaskContext c) {
        if (queue.isEmpty()) {
            stage = Stage.WALK;
            return TaskStatus.RUNNING;
        }
        Animal target = queue.getFirst();
        Leashable leash = (Leashable) target;
        if (!isPresent(c, target) || leash.getLeashData() != null || !leash.canBeLeashed()) {
            if (approach != null) {
                approach.stop(c);
                approach = null;
            }
            queue.removeFirst();
            return TaskStatus.RUNNING;
        }
        if (approach == null) {
            approach = new Approach(target.blockPosition());
        }
        Approach.State state = approach.tick(c);
        if (state == Approach.State.MOVING) {
            return TaskStatus.RUNNING;
        }
        if (state == Approach.State.UNREACHABLE) {
            approach.stop(c);
            queue.removeFirst();
            approach = null;
            return TaskStatus.RUNNING;
        }
        if (!leash.canHaveALeashAttachedTo(c.companion())) {
            queue.removeFirst();
            approach = null;
            return TaskStatus.RUNNING;
        }
        ItemStack lead = takeLead(c.companion());
        if (lead.isEmpty()) {
            queue.clear();
            approach = null;
            stage = Stage.WALK;
            return TaskStatus.RUNNING;
        }
        leash.setLeashedTo(c.companion(), true);
        leashed.add(new Attachment(target, leash.getLeashData(), lead));
        queue.removeFirst();
        approach = null;
        if (queue.isEmpty()) {
            stage = Stage.WALK;
        }
        return TaskStatus.RUNNING;
    }

    private TaskStatus walk(TaskContext c) {
        for (int index = leashed.size() - 1; index >= 0; index--) {
            if (!ownsLeash(c, leashed.get(index))) {
                leashed.remove(index);
                lost++;
            }
        }
        if (leashed.isEmpty()) {
            String reason = lost == 0 ? "could not leash any " + id : "no " + id + " reached the destination";
            return failAndRelease(c, reason);
        }
        for (Attachment attachment : leashed) {
            if (attachment.animal().position().distanceToSqr(c.companion().position())
                    > BEHIND_DISTANCE * BEHIND_DISTANCE) {
                if (waitTicks++ == 0) {
                    mover.stop(c);
                }
                if (waitTicks >= WAIT_LIMIT) {
                    return failAndRelease(c, "a leashed " + id + " could not catch up");
                }
                return TaskStatus.RUNNING;
            }
        }
        waitTicks = 0;
        if (c.companion().position().distanceToSqr(destinationCenter) <= ARRIVAL_DISTANCE * ARRIVAL_DISTANCE) {
            return finishTying(c);
        }
        return switch (mover.tick(c, destinationCenter)) {
            case MOVING -> TaskStatus.RUNNING;
            case STUCK, ARRIVED -> failAndRelease(c, "cannot reach destination " + coords(destination));
        };
    }

    private TaskStatus finishTying(TaskContext c) {
        int led = leashed.size();
        BlockPos fencePos = tie ? findFence(c.level()) : null;
        if (fencePos != null) {
            if (!c.companion().hands().canInteract(fencePos)) {
                return failAndRelease(
                        c, "the fence at " + coords(fencePos) + " is " + ProtectionText.describe(c, fencePos));
            }
            // Vanilla's player interaction only transfers leashes held by the fake player. These belong to the body.
            LeashFenceKnotEntity knot = LeashFenceKnotEntity.getOrCreateKnot(c.level(), fencePos);
            for (Attachment attachment : leashed) {
                ((Leashable) attachment.animal()).setLeashedTo(knot, true);
            }
            leashed.clear();
        } else {
            releaseOwnedLeashes(c);
        }
        mover.stop(c);
        summary = "led " + led + " " + id + " to " + coords(destination)
                + (fencePos == null ? " and let them go" : " and tied them to the fence at " + coords(fencePos))
                + lossSummary();
        finished = true;
        return TaskStatus.SUCCESS;
    }

    private TaskStatus failAndRelease(TaskContext c, String reason) {
        stop(c);
        summary = reason + lossSummary();
        failed = true;
        return TaskStatus.FAILED;
    }

    private String lossSummary() {
        return lost == 0 ? "" : "; lost " + lost + " on the way";
    }

    private void releaseOwnedLeashes(TaskContext c) {
        while (!leashed.isEmpty()) {
            Attachment attachment = leashed.removeLast();
            if (ownsLeash(c, attachment)) {
                ((Leashable) attachment.animal()).removeLeash();
                InventoryOps.insertOrDrop(c.companion(), attachment.lead());
            }
        }
    }

    private static boolean ownsLeash(TaskContext c, Attachment attachment) {
        Leashable leash = (Leashable) attachment.animal();
        return isPresent(c, attachment.animal())
                && leash.getLeashData() == attachment.leashData()
                && leash.getLeashHolder() == c.companion();
    }

    private static boolean isPresent(TaskContext c, Animal animal) {
        return animal.isAlive() && !animal.isRemoved() && animal.level() == c.level();
    }

    private @Nullable BlockPos findFence(ServerLevel level) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -FENCE_RADIUS; dx <= FENCE_RADIUS; dx++) {
            for (int dy = -FENCE_RADIUS; dy <= FENCE_RADIUS; dy++) {
                for (int dz = -FENCE_RADIUS; dz <= FENCE_RADIUS; dz++) {
                    BlockPos pos = destination.offset(dx, dy, dz);
                    if (!level.hasChunkAt(pos) || !level.getBlockState(pos).is(BlockTags.FENCES)) {
                        continue;
                    }
                    double distance = pos.distSqr(destination);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = pos.immutable();
                    }
                }
            }
        }
        return best;
    }

    private static ItemStack takeLead(CompanionEntity companion) {
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            ItemStack stack = companion.inventory().getItem(slot);
            if (stack.is(Items.LEAD)) {
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

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
