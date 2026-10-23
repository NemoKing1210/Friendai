package dev.friendai.task.builtin;

import dev.friendai.build.BlockSpec;
import dev.friendai.build.Blueprint;
import dev.friendai.build.BuildOrder;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.build.Materials;
import dev.friendai.task.build.Parts;
import dev.friendai.task.build.Placement;
import dev.friendai.task.build.Reach;
import dev.friendai.task.build.Scaffold;
import dev.friendai.task.build.Site;
import dev.friendai.task.interact.ContainerAccess;
import dev.friendai.task.interact.PlaceSpot;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import dev.friendai.task.mining.BlockBreaker;
import dev.friendai.task.nav.Approach;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Builds a blueprint block by block: checks the site and materials, takes what storage has, clears the natural
 * ground, fills the foundation, then places the blocks in build order, growing bottom-up.
 *
 * <p>The companion never breaks a block a player put there: a foreign block on the site or appearing mid-build fails
 * the task instead.
 */
public final class BuildTask implements Task {
    private static final int PROGRESS_STEP = 25;
    /** How far a block may be horizontally from the companion for a pillar to bring it into reach. */
    private static final double SCAFFOLD_RANGE = 3.8;
    /** The sides of a target a pillar may be built on, in a fixed order so the column is reproducible. */
    private static final Direction[] PILLAR_SIDES = {
        Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };

    private enum Phase { CHECK, TAKE, CLEAR, FOUNDATION, PLACE, CLEANUP }

    private final Blueprint blueprint;
    private final BlockPos origin;
    private final Rotation rotation;
    private final boolean clearSite;
    private final int tickLimit;

    private Phase phase = Phase.CHECK;
    private String summary = "";
    private int ticks;
    private TaskStatus pendingStatus;

    private Placement.PlacedBlueprint placed;
    private Site.SiteReport site;
    /** Blueprint cells that hold a block when the build is done; the scaffold never fills one of them. */
    private Set<BlockPos> plannedCells = Set.of();
    private List<BlockSpec> order = List.of();
    private int total;
    private int done;
    private int nextNotice = PROGRESS_STEP;
    private int placedCount;
    private int clearedCount;
    private int foundationCount;

    private TakeItemsTask takeTask;
    private BlockPos takeTarget;
    private final Set<BlockPos> exhaustedStorage = new HashSet<>();

    /** Temporary support and pillar blocks; null until the check starts. */
    private Scaffold scaffold;

    private final Deque<BlockPos> clearQueue = new ArrayDeque<>();
    private BlockBreaker breaker;

    private List<Placement.PlacedSpec> foundation = List.of();
    private int foundationIndex;

    private List<BlockSpec> queue = List.of();
    private int cursor;
    private List<BlockSpec> deferred = new ArrayList<>();
    /** Whether the current pass placed at least one block; a pass that places nothing is the last. */
    private boolean passProgress;

    private Approach approach;
    private BlockSpec current;
    private Placement.PlacedSpec currentPlaced;
    private BlockPos currentPos;

    public BuildTask(Blueprint blueprint, BlockPos origin, Rotation rotation, boolean clearSite) {
        this.blueprint = blueprint;
        this.origin = origin;
        this.rotation = rotation;
        this.clearSite = clearSite;
        this.tickLimit = blueprint.blocks().size() * 60 + 12_000;
    }

    @Override
    public String type() {
        return "build_structure";
    }

    @Override
    public boolean hasTimeout() {
        return false;
    }

    @Override
    public void start(TaskContext c) {}

    @Override
    public TaskStatus tick(TaskContext c) {
        if (phase != Phase.CLEANUP && ++ticks > tickLimit) {
            return fail(c, "timed out");
        }
        return switch (phase) {
            case CHECK -> check(c);
            case TAKE -> take(c);
            case CLEAR -> clear(c);
            case FOUNDATION -> buildFoundation(c);
            case PLACE -> place(c);
            case CLEANUP -> cleanup(c);
        };
    }

    private TaskStatus check(TaskContext c) {
        ServerLevel level = c.level();
        scaffold = new Scaffold(c.companion());
        placed = Placement.at(blueprint, origin, rotation);
        site = Site.check(level, placed, c.companion(), clearSite);
        // The foundation is needed to know how much dirt or cobblestone the blueprint needs, even when the site
        // check found a problem; the problem itself is reported below, after the height check as before.
        String materialError = null;
        try {
            scaffold.reserve(Materials.needed(placed.specs(), site.foundation()));
        } catch (IllegalArgumentException e) {
            materialError = e.getMessage();
        }
        plannedCells = plannedCells(placed.specs());
        String unreachable = unreachable(c);
        if (unreachable != null) {
            return fail(c, unreachable);
        }
        if (!site.problems().isEmpty()) {
            return fail(c, String.join("; ", site.problems()));
        }
        if (materialError != null) {
            return fail(c, materialError);
        }
        clearQueue.addAll(site.toClear());
        foundation = site.foundation().stream()
                .sorted(Comparator.comparingInt(spec -> spec.pos().getY()))
                .toList();
        order = BuildOrder.order(blueprint, BuildTask::needsSupport, origin.getX(), origin.getZ());
        queue = order;
        total = order.size();
        phase = Phase.TAKE;
        return TaskStatus.RUNNING;
    }

    /** A block is an attachment when it cannot hang in the air without a neighbour. */
    private static boolean needsSupport(String state) {
        return state.contains("torch") || state.contains("door") || state.contains("ladder");
    }

    /** The cells the blueprint fills with a block, which a support block must never occupy. */
    private static Set<BlockPos> plannedCells(List<Placement.PlacedSpec> specs) {
        Set<BlockPos> cells = new HashSet<>();
        for (Placement.PlacedSpec spec : specs) {
            if (!spec.wanted().isAir()) {
                cells.add(spec.pos());
            }
        }
        return Set.copyOf(cells);
    }

    /**
     * The first block too high to place from any standing ground below it, or null. When the companion carries dirt
     * or cobblestone the blueprint does not need, it can build a scaffold pillar instead, so the height is checked
     * while building. Material the blueprint needs is not scaffolding: a tower with an exact supply must be refused
     * here rather than starved of its own walls.
     */
    private String unreachable(TaskContext c) {
        if (scaffold.canScaffold()) {
            return null;
        }
        BlockPos pos = Reach.firstOutOfReach(c.level(), placed.specs(), origin);
        if (pos == null) {
            return null;
        }
        return coords(pos) + " is out of reach (build height limit without scaffolding is "
                + Reach.HEIGHT + " above standing ground)";
    }

    private TaskStatus take(TaskContext c) {
        if (takeTask != null) {
            TaskStatus status = takeTask.tick(c);
            if (status == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }
            if (status == TaskStatus.FAILED && takeTarget != null) {
                exhaustedStorage.add(takeTarget);
            }
            takeTask = null;
            takeTarget = null;
            return TaskStatus.RUNNING;
        }
        Map<Item, Integer> missing = missing(c);
        if (missing.isEmpty()) {
            phase = site.toClear().isEmpty() ? (foundation.isEmpty() ? Phase.PLACE : Phase.FOUNDATION) : Phase.CLEAR;
            return TaskStatus.RUNNING;
        }
        for (Map.Entry<Item, Integer> entry : missing.entrySet()) {
            BlockPos container = storageWith(c.level(), c.companion(), entry.getKey());
            if (container == null) {
                continue;
            }
            ItemMatcher matcher = ItemMatcher.parse(itemId(entry.getKey())).orElseThrow();
            takeTask = new TakeItemsTask(matcher, entry.getValue(), container);
            takeTask.start(c);
            takeTarget = container;
            return TaskStatus.RUNNING;
        }
        return fail(c, missingText(missing));
    }

    private Map<Item, Integer> missing(TaskContext c) {
        Map<Item, Integer> needed = Materials.needed(placed.specs(), site.foundation());
        Map<Item, Integer> have = Materials.carried(c.companion());
        Map<Item, Integer> missing = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> entry : needed.entrySet()) {
            int lack = entry.getValue() - have.getOrDefault(entry.getKey(), 0);
            if (lack > 0) {
                missing.put(entry.getKey(), lack);
            }
        }
        return missing;
    }

    private BlockPos storageWith(ServerLevel level, CompanionEntity companion, Item item) {
        ItemMatcher matcher = ItemMatcher.parse(itemId(item)).orElse(null);
        if (matcher == null) {
            return null;
        }
        for (BlockPos pos : ContainerAccess.all(level, companion.blockPosition(), Materials.STORAGE_RADIUS,
                container -> InventoryOps.count(container, matcher) > 0)) {
            if (!exhaustedStorage.contains(pos)) {
                return pos;
            }
        }
        return null;
    }

    private String missingText(Map<Item, Integer> missing) {
        StringBuilder text = new StringBuilder("missing materials: ");
        boolean first = true;
        for (Map.Entry<Item, Integer> entry : missing.entrySet()) {
            if (!first) {
                text.append(", ");
            }
            text.append(entry.getValue()).append(" × ").append(itemId(entry.getKey()));
            first = false;
        }
        return text.toString();
    }

    private TaskStatus clear(TaskContext c) {
        if (breaker == null) {
            BlockPos next = nextToClear(c.level());
            if (next == null) {
                phase = foundation.isEmpty() ? Phase.PLACE : Phase.FOUNDATION;
                return TaskStatus.RUNNING;
            }
            breaker = new BlockBreaker(next, Site::isNatural);
        }
        return switch (breaker.tick(c)) {
            case WORKING -> TaskStatus.RUNNING;
            case BROKEN -> {
                clearedCount++;
                breaker = null;
                yield TaskStatus.RUNNING;
            }
            case GONE, UNREACHABLE, PROTECTED -> {
                breaker = null;
                yield TaskStatus.RUNNING;
            }
        };
    }

    private BlockPos nextToClear(ServerLevel level) {
        while (!clearQueue.isEmpty()) {
            BlockPos pos = clearQueue.poll();
            BlockState state = level.getBlockState(pos);
            if (Site.isNatural(state) && !state.isAir()) {
                return pos;
            }
        }
        return null;
    }

    private TaskStatus buildFoundation(TaskContext c) {
        while (foundationIndex < foundation.size()) {
            Placement.PlacedSpec spec = foundation.get(foundationIndex);
            BlockState world = c.level().getBlockState(spec.pos());
            if (world.equals(spec.wanted())) {
                foundationIndex++;
                foundationCount++;
                continue;
            }
            if (approach == null) {
                approach = new Approach(spec.pos());
            }
            switch (approach.tick(c.companion())) {
                case MOVING -> {
                    return TaskStatus.RUNNING;
                }
                case UNREACHABLE -> {
                    return fail(c, "can't reach " + coords(spec.pos()));
                }
                case IN_REACH -> {
                }
            }
            Optional<PlaceSpot.Spot> spot = spot(c.level(), spec.pos());
            if (spot.isEmpty()) {
                return fail(c, "can't place " + describeState(spec.wanted()) + " at " + coords(spec.pos())
                        + ": nothing to place it against");
            }
            ItemStack held = takeOne(c, spec.wanted());
            if (held.isEmpty()) {
                return fail(c, "missing materials: 1 × " + itemId(Materials.itemFor(spec.wanted())));
            }
            c.companion().hands().place(held, spot.get().against(), spot.get().face());
            BlockState after = c.level().getBlockState(spec.pos());
            if (after.getBlock() == spec.wanted().getBlock()) {
                Parts.reconcile(c.level(), spec.pos(), spec.wanted());
                foundationCount++;
                foundationIndex++;
                approach = null;
                return TaskStatus.RUNNING;
            }
            return fail(c, "couldn't place " + describeState(spec.wanted()) + " at " + coords(spec.pos()));
        }
        phase = Phase.PLACE;
        return TaskStatus.RUNNING;
    }

    private TaskStatus place(TaskContext c) {
        ServerLevel level = c.level();
        while (true) {
            if (current == null) {
                BlockSpec next = nextSpec();
                if (next == null) {
                    if (!deferred.isEmpty() && passProgress) {
                        queue = deferred;
                        deferred = new ArrayList<>();
                        cursor = 0;
                        passProgress = false;
                        continue;
                    }
                    if (!deferred.isEmpty()) {
                        return supportStuck(c);
                    }
                    return finish(c);
                }
                current = next;
                currentPlaced = specOf(current);
                currentPos = currentPlaced.pos();
            }
            Placement.PlacedSpec wanted = currentPlaced;
            BlockState world = level.getBlockState(currentPos);
            if (wanted.wanted().isAir()) {
                current = null;
                advance(c);
                continue;
            }
            if (world.equals(wanted.wanted())) {
                current = null;
                advance(c);
                continue;
            }
            if (taken(level, currentPos, wanted.wanted())) {
                return fail(c, coords(currentPos) + " is taken by " + blockId(world));
            }
            if (standsIn(c.companion(), currentPos) && !stepAside(c, currentPos)) {
                return fail(c, coords(currentPos) + " is blocked by me and there is nowhere to step aside");
            }
            if (!inReach(c.companion(), currentPos)) {
                int feetY = pillarFeetY(c.companion(), currentPos);
                if (feetY < 0) {
                    if (approach == null) {
                        approach = new Approach(currentPos);
                    }
                    Approach.State state = approach.tick(c.companion());
                    if (state == Approach.State.MOVING) {
                        return TaskStatus.RUNNING;
                    }
                    approach = null;
                    if (state == Approach.State.IN_REACH) {
                        continue;
                    }
                }
                alignForPillar(c.level(), c.companion(), currentPos);
                feetY = pillarFeetY(c.companion(), currentPos);
                if (feetY < 0) {
                    return fail(c, "can't reach " + coords(currentPos));
                }
                switch (scaffold.pillarTo(feetY)) {
                    case CLIMBING -> {
                        return TaskStatus.RUNNING;
                    }
                    case FAILED -> {
                        return fail(c, scaffold.failure());
                    }
                    case READY -> {
                    }
                }
                continue;
            }
            c.companion().getNavigation().stop();
            if (taken(level, currentPos, wanted.wanted())) {
                return fail(c, coords(currentPos) + " is taken by " + blockId(level.getBlockState(currentPos)));
            }
            if (!c.companion().hands().canInteract(currentPos)) {
                return fail(c, coords(currentPos) + " is protected");
            }
            Optional<PlaceSpot.Spot> spot = spot(level, currentPos);
            if (spot.isEmpty()) {
                // A block without a neighbour waits to the end of the queue: a neighbour placed later in the same
                // layer is better than scaffold material, and support is only placed once nothing else can help.
                deferred.add(current);
                current = null;
                approach = null;
                continue;
            }
            ItemStack held = takeOne(c, wanted.wanted());
            if (held.isEmpty()) {
                return fail(c, "missing materials: 1 × " + itemId(Materials.itemFor(wanted.wanted())));
            }
            c.companion().hands().place(held, spot.get().against(), spot.get().face());
            BlockState after = level.getBlockState(currentPos);
            if (after.getBlock() == wanted.wanted().getBlock()) {
                Parts.reconcile(level, currentPos, wanted.wanted());
                placedCount++;
                passProgress = true;
                current = null;
                approach = null;
                advance(c);
                return TaskStatus.RUNNING;
            }
            return fail(c, "couldn't place " + describeState(wanted.wanted()) + " at " + coords(currentPos));
        }
    }

    /**
     * Nothing in the deferred list has a neighbour even after every other block had its chance: the first stuck spec
     * gets one temporary scaffold block underneath, as the last resort. A cell the blueprint fills itself, or one
     * taken by a foreign block, is never used for support: the scaffold would block that spec or break someone's
     * block. Returns to the main loop once a support is in place.
     */
    private TaskStatus supportStuck(TaskContext c) {
        Placement.PlacedSpec spec = specOf(deferred.get(0));
        BlockPos pos = spec.pos();
        BlockPos below = pos.below();
        if (plannedCells.contains(below) || taken(c.level(), below, spec.wanted())) {
            return fail(c, "can't place " + describeState(spec.wanted()) + " at " + coords(pos)
                    + ": nothing to place it against");
        }
        if (!scaffold.support(below) || spot(c.level(), pos).isEmpty()) {
            if (scaffold.outOfMaterial()) {
                return fail(c, scaffold.failure());
            }
            return fail(c, "can't place " + describeState(spec.wanted()) + " at " + coords(pos)
                    + ": nothing to place it against");
        }
        queue = deferred;
        deferred = new ArrayList<>();
        cursor = 0;
        passProgress = false;
        return TaskStatus.RUNNING;
    }

    /**
     * The feet height a pillar must reach before {@code pos} is in reach, or -1 when the companion should walk
     * closer instead. The feet go two blocks below the target so the companion's head clears the cell it fills.
     */
    private int pillarFeetY(CompanionEntity companion, BlockPos pos) {
        int feetY = pos.getY() - 2;
        if (feetY <= companion.blockPosition().getY()) {
            return -1;
        }
        if (horizontalDistance(companion, pos) > SCAFFOLD_RANGE) {
            return -1;
        }
        return feetY;
    }

    private static boolean inReach(CompanionEntity companion, BlockPos pos) {
        return companion.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= Approach.REACH;
    }

    private static double horizontalDistance(CompanionEntity companion, BlockPos pos) {
        double dx = companion.getX() - (pos.getX() + 0.5);
        double dz = companion.getZ() - (pos.getZ() + 0.5);
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Steps the companion into a reproducible column for the pillar: the first free standing column beside the
     * target in a fixed order, rather than wherever pathfinding happened to leave it. Does nothing when no such
     * column is free, so the companion climbs where it stands.
     */
    private void alignForPillar(ServerLevel level, CompanionEntity companion, BlockPos target) {
        int feetY = target.getY() - 2;
        BlockPos base = companion.blockPosition();
        if (feetY <= base.getY()) {
            return;
        }
        for (Direction side : PILLAR_SIDES) {
            BlockPos column = new BlockPos(
                    target.getX() + side.getStepX(), base.getY(), target.getZ() + side.getStepZ());
            if (!freeStanding(level, column) || !columnFree(level, column, feetY)) {
                continue;
            }
            if (column.equals(base)) {
                return;
            }
            companion.getNavigation().stop();
            companion.teleportTo(column.getX() + 0.5, column.getY(), column.getZ() + 0.5);
            return;
        }
    }

    /** Whether every cell a pillar would climb through from {@code base} up to the feet height is free. */
    private static boolean columnFree(ServerLevel level, BlockPos base, int feetY) {
        for (int y = base.getY() + 1; y <= feetY + 1; y++) {
            if (!level.getBlockState(new BlockPos(base.getX(), y, base.getZ())).canBeReplaced()) {
                return false;
            }
        }
        return true;
    }

    private BlockSpec nextSpec() {
        return cursor < queue.size() ? queue.get(cursor++) : null;
    }

    private Placement.PlacedSpec specOf(BlockSpec spec) {
        BlockPos pos = origin.offset(Placement.rotate(
                new BlockPos(spec.x(), spec.y(), spec.z()), rotation, blueprint.width(), blueprint.depth()));
        BlockState wanted = Placement.parseState(spec.state()).rotate(rotation);
        return new Placement.PlacedSpec(pos, wanted);
    }

    /** A spot for pos: a full support face first, then any placed block that can be clicked. */
    private static Optional<PlaceSpot.Spot> spot(ServerLevel level, BlockPos pos) {
        Optional<PlaceSpot.Spot> strict = PlaceSpot.spotAt(level, pos);
        return strict.isPresent() ? strict : PlaceSpot.standingSpotAt(level, pos);
    }

    /** A foreign block the companion must not break. */
    private static boolean taken(ServerLevel level, BlockPos pos, BlockState wanted) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir()
                && !state.canBeReplaced()
                && state.getFluidState().isEmpty()
                && !Site.isNatural(state)
                && state.getBlock() != wanted.getBlock();
    }

    /** Whether the companion's body overlaps pos, which makes vanilla refuse the placement. */
    private static boolean standsIn(CompanionEntity companion, BlockPos pos) {
        return companion.getBoundingBox().intersects(new AABB(pos));
    }

    /**
     * Moves the companion out of a block it is about to fill: a mob standing in the cell makes vanilla refuse the
     * placement, and a companion boxed in by the blocks it has just placed cannot walk out (a one-block pit needs a
     * jump). The free spot is within reach, so it keeps building. Returns false when there is nowhere to go.
     */
    private boolean stepAside(TaskContext c, BlockPos pos) {
        CompanionEntity companion = c.companion();
        BlockPos spot = freeSpotNear(c.level(), companion, pos);
        if (spot == null) {
            return false;
        }
        companion.teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
        approach = null;
        return true;
    }

    /** The nearest free standing spot within reach of pos at the companion's own height, or null. */
    private static BlockPos freeSpotNear(ServerLevel level, CompanionEntity companion, BlockPos pos) {
        int feetY = companion.blockPosition().getY();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance < 1.5 || distance > 3.0 || distance >= bestDistance) {
                    continue;
                }
                BlockPos candidate = new BlockPos(pos.getX() + dx, feetY, pos.getZ() + dz);
                if (candidate.equals(pos) || !freeStanding(level, candidate)) {
                    continue;
                }
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static boolean freeStanding(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).isAir()
                && level.getBlockState(pos.above()).isAir()
                && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
    }

    private void advance(TaskContext c) {
        done++;
        int percent = total == 0 ? 100 : done * 100 / total;
        while (nextNotice <= PROGRESS_STEP * 3 && percent >= nextNotice) {
            c.runner().notice(
                    "building " + blueprint.name() + ": " + nextNotice + "% (" + done + "/" + total + " blocks)");
            nextNotice += PROGRESS_STEP;
        }
    }

    private TaskStatus finish(TaskContext c) {
        summary = "built " + blueprint.name() + " at " + coords(origin) + " facing "
                + Placement.facingFor(rotation).getSerializedName() + ": " + placedCount + " placed, "
                + clearedCount + " cleared, " + foundationCount + " foundation";
        return complete(c, TaskStatus.SUCCESS);
    }

    /**
     * Ends the task, first removing any scaffold blocks it placed. The result is held back until they are gone, so
     * the summary can say whether the world is clean.
     */
    private TaskStatus complete(TaskContext c, TaskStatus status) {
        if (scaffold == null || scaffold.placed().isEmpty()) {
            return status;
        }
        pendingStatus = status;
        phase = Phase.CLEANUP;
        return TaskStatus.RUNNING;
    }

    private TaskStatus cleanup(TaskContext c) {
        switch (scaffold.removeAll()) {
            case CLIMBING -> {
                return TaskStatus.RUNNING;
            }
            case READY -> {
                if (pendingStatus == TaskStatus.SUCCESS) {
                    summary += "; used " + scaffold.used() + " scaffold blocks (removed)";
                }
                return pendingStatus;
            }
            case FAILED -> {
                // Leftover scaffolding is never part of a success: the build only counts as done once the world
                // is clean again.
                summary += "; left " + scaffold.left() + " scaffold blocks behind";
                return TaskStatus.FAILED;
            }
        }
        return TaskStatus.RUNNING;
    }

    private ItemStack takeOne(TaskContext c, BlockState wanted) {
        Item item = Materials.itemFor(wanted);
        ItemMatcher matcher = ItemMatcher.parse(itemId(item)).orElse(null);
        if (matcher != null) {
            List<ItemStack> removed = InventoryOps.remove(c.companion().inventory(), matcher, 1);
            if (!removed.isEmpty()) {
                return removed.get(0);
            }
        }
        if (c.companion().getMainHandItem().is(item)) {
            return c.companion().getMainHandItem().split(1);
        }
        return ItemStack.EMPTY;
    }

    private TaskStatus fail(TaskContext c, String text) {
        summary = text;
        return complete(c, TaskStatus.FAILED);
    }

    @Override
    public void stop(TaskContext c) {
        if (breaker != null) {
            breaker.stop(c);
        }
        if (approach != null) {
            approach.stop(c.companion());
        }
        if (takeTask != null) {
            takeTask.stop(c);
        }
        if (scaffold != null) {
            scaffold.stop();
        }
    }

    @Override
    public String describe() {
        return "building " + blueprint.name() + " (" + blueprint.blocks().size() + " blocks) at " + coords(origin);
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    private static String itemId(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static String describeState(BlockState state) {
        return BlockStateParser.serialize(state);
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
