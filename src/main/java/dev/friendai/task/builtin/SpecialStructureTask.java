package dev.friendai.task.builtin;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.pathing.Pos;
import dev.friendai.place.Place;
import dev.friendai.place.PlaceCandidate;
import dev.friendai.place.PlaceKind;
import dev.friendai.place.PlaceSource;
import dev.friendai.structure.Footprint;
import dev.friendai.structure.Outcome;
import dev.friendai.structure.SpecialStructure;
import dev.friendai.structure.StructurePlan;
import dev.friendai.structure.StructurePlanner;
import dev.friendai.structure.StructureSnapshot;
import dev.friendai.structure.StructureStep;
import dev.friendai.structure.Trigger;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.interact.PlaceSpot;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import dev.friendai.task.nav.Approach;
import dev.friendai.task.nav.MoveHelper;
import dev.friendai.task.structure.StructureSite;
import dev.friendai.task.structure.StructureSnapshotBuilder;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Builds a special structure (iron golem, snow golem, nether portal) and fires its mechanic. The pure
 * {@link StructurePlanner} decides the steps from a world snapshot; this task runs them and re-plans after the body
 * is built, so a world that disagrees with the plan is caught rather than assumed.
 */
public final class SpecialStructureTask implements Task {
    private static final int VERIFY_TICKS = 100;
    private static final int MAX_REPLANS = 3;
    private static final int MAX_STEP_ASIDE = 5;

    private final SpecialStructure structure;
    private final StructureSite site;
    private final int maxNearbySpawned;
    private final Deque<StructureStep> steps = new ArrayDeque<>();
    private final MoveHelper aside = new MoveHelper(1.0, 0.3, false);

    private boolean planned;
    private int replans;
    private int stepAsides;
    private int baselineNearby = -1;
    private int waited;
    private Task child;
    private Approach approach;
    private BlockPos asideTarget;
    private String summary = "";
    /** The dimension the site is in; a finished structure is remembered there. */
    private String dimension = "";

    public SpecialStructureTask(SpecialStructure structure, BlockPos origin, Rotation rotation, int maxNearbySpawned) {
        this.structure = structure;
        this.site = new StructureSite(origin, rotation);
        this.maxNearbySpawned = maxNearbySpawned;
    }

    @Override
    public String type() {
        return "special:" + structure.id();
    }

    @Override
    public void start(TaskContext c) {
        dimension = c.level().dimension().identifier().toString();
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        if (!planned) {
            TaskStatus status = plan(c);
            if (status != null) {
                return status;
            }
        }
        if (child != null) {
            return tickBuild(c);
        }
        StructureStep step = steps.peek();
        if (step == null) {
            return TaskStatus.SUCCESS;
        }
        return switch (step) {
            case StructureStep.Clear clear -> {
                steps.poll();
                yield TaskStatus.RUNNING;
            }
            case StructureStep.BuildBody body -> startBuild(c);
            case StructureStep.Finish finish -> finish(c, finish.trigger());
            case StructureStep.Verify verify -> verify(c, verify.outcome());
        };
    }

    /** Snapshots the world and loads the planner's steps; returns null when there are steps to run. */
    private TaskStatus plan(TaskContext c) {
        StructureSnapshot snapshot = StructureSnapshotBuilder.build(
                c.level(), c.companion(), structure, site, maxNearbySpawned);
        if (baselineNearby < 0) {
            baselineNearby = snapshot.nearbySpawned();
        }
        StructurePlan plan = StructurePlanner.plan(structure, snapshot);
        switch (plan) {
            case StructurePlan.Done done -> {
                summary = done.describe();
                return TaskStatus.SUCCESS;
            }
            case StructurePlan.Blocked blocked -> {
                if (blocked.reason() == StructurePlan.Reason.ENTITY_IN_WAY
                        && c.companion().getBoundingBox().intersects(site.box(structure))) {
                    return stepOut(c, site.origin());
                }
                summary = plan.describe();
                return TaskStatus.FAILED;
            }
            case StructurePlan.Ready ready -> {
                steps.clear();
                steps.addAll(ready.steps());
                planned = true;
                return null;
            }
        }
    }

    private TaskStatus startBuild(TaskContext c) {
        if (replans++ >= MAX_REPLANS) {
            summary = "the " + structure.id() + " body is still incomplete after building";
            return TaskStatus.FAILED;
        }
        child = new BuildTask(structure.body(), site.origin(), site.rotation(), true, true);
        child.start(c);
        return TaskStatus.RUNNING;
    }

    private TaskStatus tickBuild(TaskContext c) {
        TaskStatus status = child.tick(c);
        if (status == TaskStatus.RUNNING) {
            return status;
        }
        String childSummary = child.resultSummary();
        child.stop(c);
        child = null;
        if (status == TaskStatus.FAILED) {
            summary = childSummary;
            return TaskStatus.FAILED;
        }
        planned = false;
        return TaskStatus.RUNNING;
    }

    private TaskStatus finish(TaskContext c, Trigger trigger) {
        CompanionEntity companion = c.companion();
        BlockPos pos;
        ItemMatcher matcher;
        BlockPos against;
        Direction face;
        switch (trigger) {
            case Trigger.LastBlock last -> {
                pos = site.pos(structure, last.at());
                BlockState wanted = site.wanted(last.state());
                if (c.level().getBlockState(pos).is(wanted.getBlock())) {
                    steps.poll();
                    return TaskStatus.RUNNING;
                }
                matcher = matcherFor(BuiltInRegistries.ITEM.getKey(wanted.getBlock().asItem()).toString());
                against = null;
                face = null;
            }
            case Trigger.Ignite ignite -> {
                pos = site.pos(structure, ignite.at());
                matcher = matcherFor(ignite.item());
                against = pos.below();
                face = Direction.UP;
            }
        }
        if (matcher == null || !hasItem(companion, matcher)) {
            summary = "I don't have " + (matcher == null ? "the finishing item" : matcher.label());
            return TaskStatus.FAILED;
        }
        if (approach == null) {
            approach = new Approach(pos);
        }
        switch (approach.tick(companion)) {
            case UNREACHABLE -> {
                summary = "can't reach " + coords(pos);
                return TaskStatus.FAILED;
            }
            case MOVING -> {
                return TaskStatus.RUNNING;
            }
            case IN_REACH -> {
            }
        }
        if (companion.getBoundingBox().intersects(site.box(structure))) {
            return stepOut(c, pos);
        }
        if (!companion.hands().canInteract(pos)) {
            summary = coords(pos) + " is protected";
            return TaskStatus.FAILED;
        }
        if (against == null) {
            Optional<PlaceSpot.Spot> spot = PlaceSpot.spotAt(c.level(), pos);
            if (spot.isEmpty()) {
                summary = "nothing to place against at " + coords(pos);
                return TaskStatus.FAILED;
            }
            against = spot.get().against();
            face = spot.get().face();
        }
        ItemStack held = takeOne(companion, matcher);
        if (held.isEmpty()) {
            summary = "I don't have " + matcher.label();
            return TaskStatus.FAILED;
        }
        companion.hands().place(held, against, face);
        approach.stop(companion);
        approach = null;
        waited = 0;
        steps.poll();
        return TaskStatus.RUNNING;
    }

    private TaskStatus verify(TaskContext c, Outcome outcome) {
        StructureSnapshot snapshot = StructureSnapshotBuilder.build(
                c.level(), c.companion(), structure, site, maxNearbySpawned);
        boolean fired = switch (outcome) {
            case Outcome.EntitySpawned spawned -> snapshot.nearbySpawned() > baselineNearby;
            case Outcome.BlocksPresent present -> snapshot.outcomeAlreadyPresent();
        };
        if (fired) {
            summary = "built " + structure.id() + " at " + coords(site.origin());
            steps.poll();
            return TaskStatus.SUCCESS;
        }
        if (++waited > VERIFY_TICKS) {
            summary = "built the " + structure.id() + " but it did not activate";
            return TaskStatus.FAILED;
        }
        return TaskStatus.RUNNING;
    }

    /**
     * Walks the companion out of the footprint to a free spot within reach of {@code near}. SUCCESS means it is out;
     * RUNNING means it is on its way.
     */
    private TaskStatus stepOut(TaskContext c, BlockPos near) {
        CompanionEntity companion = c.companion();
        AABB box = site.box(structure);
        if (asideTarget == null) {
            if (!companion.getBoundingBox().intersects(box)) {
                return TaskStatus.RUNNING;
            }
            if (stepAsides++ >= MAX_STEP_ASIDE) {
                summary = "I can't get out of the way of the " + structure.id();
                return TaskStatus.FAILED;
            }
            asideTarget = freeSpotOutside(c.level(), companion, box, near);
            if (asideTarget == null) {
                summary = "no room to step away from the " + structure.id() + " site";
                return TaskStatus.FAILED;
            }
        }
        if (aside.tick(companion, Vec3.atBottomCenterOf(asideTarget)) == MoveHelper.State.MOVING) {
            return TaskStatus.RUNNING;
        }
        aside.reset(c);
        asideTarget = null;
        return TaskStatus.RUNNING;
    }

    private static BlockPos freeSpotOutside(ServerLevel level, CompanionEntity companion, AABB box, BlockPos near) {
        int feetY = companion.blockPosition().getY();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        BlockPos center = BlockPos.containing(box.getCenter());
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -5; dx <= 5; dx++) {
                for (int dz = -5; dz <= 5; dz++) {
                    BlockPos candidate = new BlockPos(center.getX() + dx, feetY + dy, center.getZ() + dz);
                    if (new AABB(candidate).intersects(box) || !freeStanding(level, candidate)) {
                        continue;
                    }
                    if (Vec3.atCenterOf(near).distanceTo(Vec3.atCenterOf(candidate).add(0, 1, 0)) > Approach.REACH) {
                        continue;
                    }
                    double distance = companion.position().distanceTo(Vec3.atBottomCenterOf(candidate));
                    if (distance < bestDistance) {
                        best = candidate;
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    private static boolean freeStanding(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).isAir()
                && level.getBlockState(pos.above()).isAir()
                && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
    }

    private static ItemMatcher matcherFor(String itemId) {
        return ItemMatcher.parse(itemId).orElse(null);
    }

    private static boolean hasItem(CompanionEntity companion, ItemMatcher matcher) {
        return InventoryOps.count(companion.inventory(), matcher) > 0 || matcher.test(companion.getMainHandItem());
    }

    private static ItemStack takeOne(CompanionEntity companion, ItemMatcher matcher) {
        List<ItemStack> removed = InventoryOps.remove(companion.inventory(), matcher, 1);
        if (!removed.isEmpty()) {
            return removed.get(0);
        }
        if (matcher.test(companion.getMainHandItem())) {
            return companion.getMainHandItem().split(1);
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void stop(TaskContext c) {
        if (child != null) {
            child.stop(c);
            child = null;
        }
        if (approach != null) {
            approach.stop(c.companion());
        }
        aside.stop(c);
    }

    @Override
    public String describe() {
        return "building the " + structure.id() + " at " + coords(site.origin());
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    /**
     * The structure that now stands on the site: an iron golem is the farm it guards, a nether portal is a portal,
     * and anything else is a landmark named after its id.
     */
    @Override
    public PlaceCandidate placeCandidate() {
        if (dimension.isBlank()) {
            return null;
        }
        PlaceKind kind = switch (structure.id()) {
            case "iron_golem" -> PlaceKind.FARM;
            case "nether_portal" -> PlaceKind.PORTAL;
            default -> PlaceKind.LANDMARK;
        };
        String label = switch (kind) {
            case FARM -> "iron";
            case PORTAL -> "";
            default -> structure.id();
        };
        Footprint footprint = structure.footprint();
        int span = Math.max(footprint.width(), footprint.depth()) / 2 + 2;
        BlockPos anchor = site.origin();
        return new PlaceCandidate(kind, label, "", dimension,
                new Pos(anchor.getX(), anchor.getY(), anchor.getZ()),
                Math.clamp(span, 1, Place.RADIUS_MAX), PlaceSource.STRUCTURE);
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
