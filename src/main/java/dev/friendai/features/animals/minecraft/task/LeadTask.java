package dev.friendai.features.animals.minecraft.task;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.animals.minecraft.Animals;
import dev.friendai.features.navigation.minecraft.nav.Approach;
import dev.friendai.features.navigation.minecraft.nav.MoveHelper;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.inventory.DropSettle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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

/** Leads animals to a place, then ties them to a fence or lets them go. */
public final class LeadTask implements Task {
    private static final int RADIUS = 16;
    private static final int FENCE_RADIUS = 3;
    private static final int WAIT_LIMIT = 100;
    private static final double BEHIND_DISTANCE = 6.0;
    /** How long a released leash gets to land and leave its pickup delay before the task reports. */
    private static final int SETTLE_TICKS = 12;

    private enum Stage {
        ATTACH,
        WALK,
        COLLECT,
        DONE
    }

    private final EntityType<?> type;
    private final int count;
    private final BlockPos destination;
    private final boolean tie;
    private final String id;
    private final MoveHelper mover = new MoveHelper(0.8, 1.5);
    private final Set<UUID> unreachable = new HashSet<>();
    private final List<Animal> queue = new ArrayList<>();
    private final List<Animal> leashed = new ArrayList<>();
    private Vec3 origin;
    private boolean preflightDone;
    private boolean failed;
    private boolean finished;
    private String summary = "";
    private Stage stage = Stage.ATTACH;
    private Animal target;
    private Approach approach;
    private int waitTicks;
    private int lost;
    /** The wait for the freed leashes to land, and the summary to report when it is over. */
    private DropSettle settle;

    private String pendingSummary = "";

    public LeadTask(EntityType<?> type, int count, BlockPos destination, boolean tie) {
        this.type = type;
        this.count = count;
        this.destination = destination;
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
                    .filter(animal -> ((Leashable) animal).canBeLeashed() && !((Leashable) animal).isLeashed())
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
            case COLLECT -> collect(c);
            case DONE -> TaskStatus.SUCCESS;
        };
    }

    @Override
    public void stop(TaskContext c) {
        mover.stop(c);
        settle = null;
        c.companion().getNavigation().stop();
    }

    @Override
    public String describe() {
        return "leading " + id + " to " + coords(destination);
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    private TaskStatus attach(TaskContext c) {
        if (queue.isEmpty()) {
            stage = Stage.WALK;
            return TaskStatus.RUNNING;
        }
        target = queue.get(0);
        if (!target.isAlive() || target.isRemoved()) {
            queue.remove(0);
            approach = null;
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
            unreachable.add(target.getUUID());
            queue.remove(0);
            target = null;
            approach = null;
            return TaskStatus.RUNNING;
        }
        ItemStack lead = takeOne(c.companion(), Items.LEAD);
        if (lead.isEmpty()) {
            queue.clear();
            target = null;
            approach = null;
            stage = Stage.WALK;
            return TaskStatus.RUNNING;
        }
        ((Leashable) target).setLeashedTo(c.companion(), true);
        leashed.add(target);
        queue.remove(0);
        target = null;
        approach = null;
        if (queue.isEmpty()) {
            stage = Stage.WALK;
        }
        return TaskStatus.RUNNING;
    }

    private TaskStatus walk(TaskContext c) {
        for (int index = leashed.size() - 1; index >= 0; index--) {
            Animal animal = leashed.get(index);
            if (!animal.isAlive() || animal.isRemoved() || !((Leashable) animal).isLeashed()) {
                leashed.remove(index);
                lost++;
            }
        }
        if (leashed.isEmpty()) {
            return finishTying(c, Optional.empty());
        }
        boolean behind = leashed.stream()
                .anyMatch(animal ->
                        animal.position().distanceToSqr(c.companion().position()) > BEHIND_DISTANCE * BEHIND_DISTANCE);
        if (behind) {
            waitTicks++;
            if (waitTicks <= WAIT_LIMIT) {
                return TaskStatus.RUNNING;
            }
        } else {
            waitTicks = 0;
        }
        mover.tick(c, Vec3.atCenterOf(destination));
        if (c.companion().position().distanceToSqr(Vec3.atCenterOf(destination)) <= 4.0) {
            return finishTying(c, findFence(c.level()));
        }
        return TaskStatus.RUNNING;
    }

    private TaskStatus finishTying(TaskContext c, Optional<BlockPos> fence) {
        int led = leashed.size();
        boolean tied = false;
        BlockPos fencePos = fence.orElse(null);
        if (tie && fencePos != null) {
            LeashFenceKnotEntity knot = LeashFenceKnotEntity.getOrCreateKnot(c.level(), fencePos);
            for (Animal animal : leashed) {
                ((Leashable) animal).setLeashedTo(knot, true);
            }
            tied = true;
        } else {
            for (Animal animal : leashed) {
                ((Leashable) animal).dropLeash();
            }
        }
        StringBuilder text = new StringBuilder("led ")
                .append(led)
                .append(' ')
                .append(id)
                .append(" to ")
                .append(coords(destination));
        if (tied) {
            text.append(" and tied them to the fence at ").append(coords(fencePos));
        } else {
            text.append(" and let them go");
        }
        if (lost > 0) {
            text.append("; lost ").append(lost).append(" on the way");
        }
        if (!tied) {
            // The freed leashes lie on the ground: take them before the task reports.
            pendingSummary = text.toString();
            settle = new DropSettle(SETTLE_TICKS, DropSettle.INTERACT_RADIUS, null);
            stage = Stage.COLLECT;
            return TaskStatus.RUNNING;
        }
        summary = text.toString();
        stage = Stage.DONE;
        finished = true;
        return TaskStatus.SUCCESS;
    }

    private TaskStatus collect(TaskContext c) {
        if (!settle.tick(c)) {
            return TaskStatus.RUNNING;
        }
        settle = null;
        summary = pendingSummary;
        pendingSummary = "";
        stage = Stage.DONE;
        finished = true;
        return TaskStatus.SUCCESS;
    }

    private Optional<BlockPos> findFence(ServerLevel level) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -FENCE_RADIUS; dx <= FENCE_RADIUS; dx++) {
            for (int dy = -FENCE_RADIUS; dy <= FENCE_RADIUS; dy++) {
                for (int dz = -FENCE_RADIUS; dz <= FENCE_RADIUS; dz++) {
                    BlockPos pos = destination.offset(dx, dy, dz);
                    if (!level.getBlockState(pos).is(BlockTags.FENCES)) {
                        continue;
                    }
                    double distance = Vec3.atCenterOf(pos).distanceToSqr(Vec3.atCenterOf(destination));
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = pos.immutable();
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static ItemStack takeOne(CompanionEntity companion, net.minecraft.world.item.Item item) {
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

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
