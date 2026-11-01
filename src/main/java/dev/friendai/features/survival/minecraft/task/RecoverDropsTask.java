package dev.friendai.features.survival.minecraft.task;

import dev.friendai.entity.CompanionCarryData;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.survival.DeathRecord;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.inventory.DropCollector;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Walks back to where the companion died and collects what it dropped there.
 *
 * <p>The walk and the pickup are one task on purpose: the drops are recorded with the item entities they spawned, so
 * the collector takes those and nothing else, and a spot that turned into lava or fire is refused instead of walked
 * into. The record is cleared when the task ends in any way, so a companion that gave up does not retry forever; a
 * refusal from the owner stops it before the walk starts.
 */
public final class RecoverDropsTask implements Task {
    /** How far around the death spot a drop may lie; the death itself can throw a stack a few blocks. */
    private static final int COLLECT_RADIUS = 16;
    /** How far the collector is asked to look; the death itself can throw a stack a few blocks. */
    private static final int MAX_REACH = 96;
    /**
     * Older than this and the items are gone: vanilla removes an item entity after {@code ItemEntity.LIFETIME}
     * (6000 ticks), so a trip is promised only inside the margin.
     */
    private static final long DEADLINE_TICKS = 5_400;
    /** A round that moves nothing for this long is reported instead of retried until the deadline. */
    private static final long STALLED_TICKS = 200;
    /** A collector that never saw one of our drops stops after this long instead of scanning forever. */
    private static final long EMPTY_SEARCH_TICKS = 100;
    /** Below this health or food the trip is abandoned rather than risked. */
    private static final int MIN_HEALTH = 6;

    private static final int MIN_FOOD = 6;

    private @Nullable BlockPos target;
    private @Nullable UUID owner;
    private Set<UUID> recorded = Set.of();
    private int expectedStacks;
    private @Nullable DropCollector collector;
    private boolean failed;
    private long startedAt;
    private long lastProgressAt;
    private int lastCollected;
    private String summary = "no drops to recover";

    @Override
    public String type() {
        return "recover_drops";
    }

    @Override
    public void start(TaskContext c) {
        CompanionEntity companion = c.companion();
        owner = companion.ownerUuid();
        if (owner == null) {
            fail("I have no owner to recover things for");
            return;
        }
        CompanionCarryData store = CompanionCarryData.get(c.level().getServer());
        if (store.refusedRecovery(owner)) {
            abandon(c, "you told me not to go back for my things");
            return;
        }
        Optional<DeathRecord> open = store.openDrops(owner);
        if (open.isEmpty()) {
            fail("I left nothing behind");
            return;
        }
        DeathRecord death = open.get();
        if (!death.dimension().equals(c.level().dimension().identifier().getPath())) {
            abandon(c, "my things are still in " + death.dimension() + ", and I am not there");
            return;
        }
        if (death.drops().unrecoverable()) {
            abandon(c, "my things burnt or fell where I cannot reach them");
            return;
        }
        if (tooLate(c, death)) {
            abandon(c, "my things are long gone by now");
            return;
        }
        if (companion.getHealth() < MIN_HEALTH || c.foodLevel() < MIN_FOOD) {
            fail("I am too hurt or too hungry for that trip");
            return;
        }
        target = new BlockPos(death.x(), death.y(), death.z());
        recorded = Set.copyOf(death.drops().entities());
        expectedStacks = Math.max(death.drops().stacks().size(), recorded.size());
        if (hazard(c, target)) {
            target = null;
            abandon(c, "the place I died is on fire or full of lava");
            return;
        }
        double away = companion.position().distanceTo(Vec3.atCenterOf(target));
        if (away > MAX_REACH) {
            abandon(c, "where I died is too far to walk from here");
            return;
        }
        // The collector navigates to each drop itself, so the trip needs no separate walking phase.
        int reach = (int) Math.min(MAX_REACH, Math.ceil(away) + COLLECT_RADIUS);
        collector = new DropCollector(companion.position(), reach, null, recorded);
        startedAt = c.level().getGameTime();
        lastProgressAt = startedAt;
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        if (failed) {
            return TaskStatus.FAILED;
        }
        if (target == null || owner == null) {
            summary = "no drops to recover";
            return TaskStatus.SUCCESS;
        }
        if (collector == null) {
            summary = "I got back to the spot but left nothing there";
            finish(c);
            return TaskStatus.SUCCESS;
        }
        DropCollector.State state = collector.tick(c);
        if (collector.collected() != lastCollected) {
            lastCollected = collector.collected();
            lastProgressAt = c.level().getGameTime();
        }
        if (state == DropCollector.State.COLLECTING) {
            // Nothing of ours lies there any more (the owner took it, it burnt, it despawned): stop looking.
            if (!collector.sawIntended() && c.level().getGameTime() - startedAt > EMPTY_SEARCH_TICKS) {
                summary = "I got back to the spot and my things were gone";
                finish(c);
                return TaskStatus.SUCCESS;
            }
            // A stalled round (no room in the bag, a drop behind a wall) reports what is left instead of looping.
            if (c.level().getGameTime() - lastProgressAt > STALLED_TICKS) {
                summary = "fetched " + collector.collectedStacks().size() + " of " + expectedStacks
                        + " stacks; the rest stay on the ground";
                finish(c);
                return TaskStatus.SUCCESS;
            }
            return TaskStatus.RUNNING;
        }
        int recovered = collector.collectedStacks().size();
        summary = "recovered " + recovered + " of " + expectedStacks + " stacks";
        if (!collector.leftovers().isEmpty()) {
            summary += ", " + collector.leftovers().size() + " left on the ground";
        }
        finish(c);
        return TaskStatus.SUCCESS;
    }

    @Override
    public void stop(TaskContext c) {
        if (collector != null) {
            collector.stop(c);
        }
    }

    @Override
    public String describe() {
        return target == null
                ? "looking for what I dropped"
                : "fetching what I dropped at " + target.getX() + " " + target.getY() + " " + target.getZ();
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    private void fail(String reason) {
        failed = true;
        summary = reason;
    }

    /** A terminal refusal: the record is dropped so the same hopeless trip is not planned again. */
    private void abandon(TaskContext c, String reason) {
        fail(reason);
        finish(c);
    }

    /** Ends the attempt for good: the record is cleared so a finished trip is not retried. */
    private void finish(TaskContext c) {
        if (owner != null) {
            CompanionCarryData.get(c.level().getServer()).clearDrops(owner);
        }
    }

    private static boolean tooLate(TaskContext c, DeathRecord death) {
        return c.level().getGameTime() - death.tick() > DEADLINE_TICKS;
    }

    private static boolean hazard(TaskContext c, BlockPos pos) {
        return c.level().getFluidState(pos).is(FluidTags.LAVA)
                || c.level().getBlockState(pos).is(Blocks.FIRE)
                || c.level().getBlockState(pos).is(Blocks.LAVA);
    }

    /** Whether the record still has drops this task can look for. */
    public static boolean anythingToFetch(TaskContext c, UUID owner) {
        return CompanionCarryData.get(c.level().getServer())
                .openDrops(owner)
                .filter(death -> !death.drops().unrecoverable())
                .filter(death -> !death.dimension().isEmpty())
                .isPresent();
    }

    /** The recorded entity ids, for a caller that wants to know what the trip is about without starting it. */
    public static List<UUID> recordedEntities(TaskContext c, UUID owner) {
        return CompanionCarryData.get(c.level().getServer())
                .openDrops(owner)
                .map(death -> death.drops().entities())
                .orElse(List.of());
    }
}
