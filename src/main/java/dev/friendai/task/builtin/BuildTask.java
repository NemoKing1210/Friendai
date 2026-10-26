package dev.friendai.task.builtin;

import dev.friendai.build.BlockSpec;
import dev.friendai.build.Blueprint;
import dev.friendai.build.BuildOrder;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.build.BlueprintStore;
import dev.friendai.task.build.Materials;
import dev.friendai.task.build.Parts;
import dev.friendai.task.build.Placement;
import dev.friendai.task.build.Reach;
import dev.friendai.task.build.Scaffold;
import dev.friendai.task.build.ScaffoldCleanupTask;
import dev.friendai.task.build.Site;
import dev.friendai.task.interact.ContainerAccess;
import dev.friendai.task.interact.PlaceSpot;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import dev.friendai.task.mining.BlockBreaker;
import dev.friendai.task.nav.Approach;
import dev.friendai.task.nav.MoveHelper;
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
import java.util.UUID;
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
    /** The production way to obtain a missing material; tests replace it to tighten the obtain limits. */
    private static final ObtainFactory DEFAULT_OBTAIN =
            (item, count) -> new ObtainTask(item, count, true, true);

    /** Creates the task that obtains one missing material for a build. */
    @FunctionalInterface
    public interface ObtainFactory {
        Task create(String item, int count);
    }

    private enum Phase { CHECK, TAKE, CLEAR, FOUNDATION, PLACE, CLEANUP }

    private final Blueprint blueprint;
    private final BlockPos origin;
    private final Rotation rotation;
    private final boolean clearSite;
    /** Whether missing materials may be harvested or crafted with {@code obtain_item} instead of failing. */
    private final boolean gather;
    private final ObtainFactory obtainFactory;
    private final int tickLimit;
    /** The file id of this build: kept across a reload so the same in-progress file is resumed. */
    private final String id;
    /** Scaffold blocks a restored build found in the world; recorded, never placed or paid for again. */
    private final List<BlockPos> adoptedScaffold;

    /** Whether the in-progress file has been written, so later ticks may update it. */
    private boolean inProgressStarted;
    private int writtenScaffoldCount;

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

    /** The obtain task working on one missing material; null when none is running. */
    private Task gatherTask;
    private Item gatherItem;
    private int gatherCount;
    /** Materials already asked an obtain task for, so a short result cannot start the same request again. */
    private final Set<Item> gathered = new HashSet<>();

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

    private enum Column { ALIGNED, WALKING, NONE }
    private final MoveHelper pillarMover = new MoveHelper(1.0, 0.3, false);
    private final MoveHelper asideMover = new MoveHelper(1.0, 0.3, false);
    private BlockPos asideTarget;
    private Integer pillarTargetY;
    private Approach approach;
    private BlockSpec current;
    private Placement.PlacedSpec currentPlaced;
    private BlockPos currentPos;

    public BuildTask(Blueprint blueprint, BlockPos origin, Rotation rotation, boolean clearSite) {
        this(blueprint, origin, rotation, clearSite, false);
    }

    public BuildTask(Blueprint blueprint, BlockPos origin, Rotation rotation, boolean clearSite, boolean gather) {
        this(blueprint, origin, rotation, clearSite, gather, DEFAULT_OBTAIN);
    }

    /** The full constructor, for tests that need tighter obtain limits through their own factory. */
    public BuildTask(Blueprint blueprint, BlockPos origin, Rotation rotation, boolean clearSite, boolean gather,
            ObtainFactory obtainFactory) {
        this(blueprint, origin, rotation, clearSite, gather, obtainFactory, newId(), List.of());
    }

    private BuildTask(Blueprint blueprint, BlockPos origin, Rotation rotation, boolean clearSite, boolean gather,
            ObtainFactory obtainFactory, String id, List<BlockPos> adoptedScaffold) {
        this.blueprint = blueprint;
        this.origin = origin;
        this.rotation = rotation;
        this.clearSite = clearSite;
        this.gather = gather;
        this.obtainFactory = obtainFactory;
        this.tickLimit = blueprint.blocks().size() * 60 + 12_000;
        this.id = id;
        this.adoptedScaffold = List.copyOf(adoptedScaffold);
    }

    /**
     * A task that resumes a build saved before a reload: same blueprint and placement, same file id, and the scaffold
     * the earlier session left in the world. The check runs again, so blocks that are already correct are skipped.
     */
    public static BuildTask resume(Blueprint blueprint, BlockPos origin, Rotation rotation, boolean clearSite,
            boolean gather, List<BlockPos> scaffold, String id) {
        return new BuildTask(blueprint, origin, rotation, clearSite, gather, DEFAULT_OBTAIN, id, scaffold);
    }

    /** A fresh file id: a UUID without dashes, so it is a legal, unique file name. */
    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public String type() {
        return "build_structure";
    }

    @Override
    public boolean hasTimeout() {
        return false;
    }

    /**
     * {@code build:<id>,<x>,<y>,<z>,<facing>,<clearSite>,<gather>}. The blueprint and the scaffold live in the
     * in-progress file named by {@code <id>}, under the world's blueprint directory.
     */
    @Override
    public String saveData() {
        return "build:" + id + "," + origin.getX() + "," + origin.getY() + "," + origin.getZ() + ","
                + Placement.facingFor(rotation).getSerializedName() + "," + clearSite + "," + gather;
    }

    @Override
    public void start(TaskContext c) {}

    @Override
    public TaskStatus tick(TaskContext c) {
        TaskStatus status;
        if (phase != Phase.CLEANUP && ++ticks > tickLimit) {
            status = fail(c, "timed out");
        } else {
            status = switch (phase) {
                case CHECK -> check(c);
                case TAKE -> take(c);
                case CLEAR -> clear(c);
                case FOUNDATION -> buildFoundation(c);
                case PLACE -> place(c);
                case CLEANUP -> cleanup(c);
            };
        }
        if (status == TaskStatus.RUNNING) {
            syncInProgress(c);
        } else {
            // Every terminal path — success, failure after cleanup — leaves no in-progress file behind.
            deleteInProgress(c);
        }
        return status;
    }

    private TaskStatus check(TaskContext c) {
        ServerLevel level = c.level();
        scaffold = new Scaffold(c.companion());
        scaffold.adopt(adoptedScaffold);
        placed = Placement.at(blueprint, origin, rotation);
        site = Site.check(level, placed, c.companion(), clearSite);
        // A resumed build must not ask for the material of blocks that are already in the world, nor reserve it as
        // scaffold surplus: only what is still to place counts.
        List<Placement.PlacedSpec> remaining = remaining(level);
        // The foundation is needed to know how much dirt or cobblestone the blueprint needs, even when the site
        // check found a problem; the problem itself is reported below, after the height check as before.
        String materialError = null;
        try {
            scaffold.reserve(Materials.needed(remaining, site.foundation()));
        } catch (IllegalArgumentException e) {
            materialError = e.getMessage();
        }
        plannedCells = plannedCells(placed.specs());
        String unreachable = unreachable(c, remaining);
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
        writeInProgress(c);
        return TaskStatus.RUNNING;
    }

    /** The specs the world does not already hold; a resumed build only has to work on these. */
    private List<Placement.PlacedSpec> remaining(ServerLevel level) {
        List<Placement.PlacedSpec> remaining = new ArrayList<>(placed.specs().size());
        for (Placement.PlacedSpec spec : placed.specs()) {
            if (!level.getBlockState(spec.pos()).equals(spec.wanted())) {
                remaining.add(spec);
            }
        }
        return remaining;
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
    private String unreachable(TaskContext c, List<Placement.PlacedSpec> remaining) {
        if (scaffold.canScaffold()) {
            return null;
        }
        BlockPos pos = Reach.firstOutOfReach(c.level(), remaining, origin);
        if (pos == null) {
            return null;
        }
        return coords(pos) + " is out of reach (build height limit without scaffolding is "
                + Reach.HEIGHT + " above standing ground)";
    }

    /**
     * Tries storage for every missing material first, then, when gathering is allowed, obtains what storage could not
     * supply one material at a time for exactly the missing count. Anything that fails here fails the build before the
     * site is cleared or a block is placed, so a failed gather leaves the world untouched.
     */
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
        if (gatherTask != null) {
            TaskStatus status = gatherTask.tick(c);
            if (status == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }
            String obtainSummary = gatherTask.resultSummary();
            Item item = gatherItem;
            int count = gatherCount;
            gatherTask = null;
            gatherItem = null;
            if (status == TaskStatus.FAILED) {
                return fail(c, "couldn't gather " + count + " × " + itemId(item) + ": " + obtainSummary);
            }
            // A gather that reports success but leaves the item short falls through to the usual materials failure;
            // the gathered set keeps the same request from being started again.
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
        if (gather) {
            for (Map.Entry<Item, Integer> entry : missing.entrySet()) {
                if (!gathered.add(entry.getKey())) {
                    continue;
                }
                gatherItem = entry.getKey();
                gatherCount = entry.getValue();
                gatherTask = obtainFactory.create(itemId(gatherItem), gatherCount);
                gatherTask.start(c);
                return TaskStatus.RUNNING;
            }
        }
        return fail(c, missingText(missing));
    }

    private Map<Item, Integer> missing(TaskContext c) {
        Map<Item, Integer> needed = Materials.needed(remaining(c.level()), site.foundation());
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
        for (BlockPos pos : ContainerAccess.known(companion, Materials.STORAGE_RADIUS)) {
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
            // Excavating the site: a cell inside a hill has no view from any standing spot, and the build needs it
            // cleared anyway, so this breaker does not require line of sight.
            breaker = new BlockBreaker(next, Site::isNatural, false);
        }
        return switch (breaker.tick(c)) {
            case WORKING -> TaskStatus.RUNNING;
            case BROKEN -> {
                clearedCount++;
                breaker = null;
                yield TaskStatus.RUNNING;
            }
            case GONE, UNREACHABLE, PROTECTED, OUT_OF_SIGHT -> {
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
        // Finish jump/place before reevaluating reach or moving to another column.
        if (pillarTargetY != null) {
            switch (scaffold.pillarTo(pillarTargetY)) {
                case CLIMBING -> { return TaskStatus.RUNNING; }
                case FAILED -> {
                    pillarTargetY = null;
                    return fail(c, scaffold.failure());
                }
                case READY -> pillarTargetY = null;
            }
        }
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
                resetMovers(c);
                advance(c);
                continue;
            }
            if (world.equals(wanted.wanted())) {
                current = null;
                resetMovers(c);
                advance(c);
                continue;
            }
            if (taken(level, currentPos, wanted.wanted())) {
                return fail(c, coords(currentPos) + " is taken by " + blockId(world));
            }
            if (asideTarget != null || standsIn(c.companion(), currentPos)) {
                if (stepAside(c, currentPos) == TaskStatus.FAILED) {
                    return fail(c, coords(currentPos) + " is blocked by me and there is nowhere to step aside");
                }
                return TaskStatus.RUNNING;
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
                    if (state == Approach.State.IN_REACH && inReach(c.companion(), currentPos)) {
                        continue;
                    }
                }
                if (pillarColumn(c, currentPos) == Column.WALKING) {
                    return TaskStatus.RUNNING;
                }
                feetY = pillarFeetY(c.companion(), currentPos);
                if (feetY < 0) {
                    return fail(c, "can't reach " + coords(currentPos));
                }
                pillarTargetY = feetY;
                return TaskStatus.RUNNING;
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
                resetMovers(c);
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
                resetMovers(c);
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
    private Column pillarColumn(TaskContext c, BlockPos target) {
        CompanionEntity companion = c.companion();
        int feetY = target.getY() - 2;
        BlockPos base = companion.blockPosition();
        if (feetY <= base.getY()) {
            return Column.ALIGNED;
        }
        for (Direction side : PILLAR_SIDES) {
            BlockPos column = new BlockPos(target.getX() + side.getStepX(), base.getY(),
                    target.getZ() + side.getStepZ());
            if (!freeStanding(c.level(), column) || !columnFree(c.level(), column, feetY)) {
                continue;
            }
            return switch (pillarMover.tick(companion, Vec3.atBottomCenterOf(column))) {
                case ARRIVED -> {
                    pillarMover.reset(c);
                    yield Column.ALIGNED;
                }
                case STUCK -> {
                    pillarMover.reset(c);
                    yield Column.NONE;
                }
                default -> Column.WALKING;
            };
        }
        return Column.NONE;
    }

    /**
     * Whether every cell a pillar would climb through from {@code base} up to the feet height is free and none of
     * them is a blueprint cell the build has yet to fill: a pillar raised through one would leave the companion
     * standing where a block has to go.
     */
    private boolean columnFree(ServerLevel level, BlockPos base, int feetY) {
        for (int y = base.getY() + 1; y <= feetY + 1; y++) {
            BlockPos pos = new BlockPos(base.getX(), y, base.getZ());
            if (plannedCells.contains(pos) || !level.getBlockState(pos).canBeReplaced()) {
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
     * jump). The free spot is within reach, so it keeps building. Walking takes several ticks; an unreachable spot fails.
     */
    private TaskStatus stepAside(TaskContext c, BlockPos pos) {
        CompanionEntity companion = c.companion();
        if (asideTarget == null) {
            asideTarget = freeSpotNear(c.level(), companion, pos);
            if (asideTarget == null) {
                return TaskStatus.FAILED;
            }
            if (approach != null) {
                approach.stop(companion);
                approach = null;
            }
        }
        MoveHelper.State state = asideMover.tick(companion, Vec3.atBottomCenterOf(asideTarget));
        if (state == MoveHelper.State.MOVING) {
            return TaskStatus.RUNNING;
        }
        asideMover.reset(c);
        asideTarget = null;
        return state == MoveHelper.State.ARRIVED ? TaskStatus.RUNNING : TaskStatus.FAILED;
    }

    /**
     * The nearest free standing spot within reach of {@code pos}, including a block above or below the companion.
     * A partially built wall often leaves the companion standing on its top: requiring the old feet height then
     * rejects every adjacent landing even though it can safely step down or up once.
     */
    private static BlockPos freeSpotNear(ServerLevel level, CompanionEntity companion, BlockPos pos) {
        int feetY = companion.blockPosition().getY();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dy = -3; dy <= 3; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    double horizontal = Math.sqrt(dx * dx + dz * dz);
                    if (horizontal < 1.5 || horizontal > 3.0) {
                        continue;
                    }
                    BlockPos candidate = new BlockPos(pos.getX() + dx, feetY + dy, pos.getZ() + dz);
                    if (candidate.equals(pos) || !freeStanding(level, candidate)) {
                        continue;
                    }
                    double distance = companion.getEyePosition().distanceTo(Vec3.atCenterOf(candidate));
                    if (distance > Approach.REACH || distance >= bestDistance) {
                        continue;
                    }
                    best = candidate;
                    bestDistance = distance;
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

    private void resetMovers(TaskContext c) {
        pillarMover.reset(c);
        asideMover.reset(c);
        asideTarget = null;
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
                scaffold.spend(item);
                return removed.get(0);
            }
        }
        if (c.companion().getMainHandItem().is(item)) {
            ItemStack held = c.companion().getMainHandItem().split(1);
            if (!held.isEmpty()) {
                scaffold.spend(item);
            }
            return held;
        }
        return ItemStack.EMPTY;
    }

    private TaskStatus fail(TaskContext c, String text) {
        summary = text;
        return complete(c, TaskStatus.FAILED);
    }

    /** Leftover scaffold is the one thing a cancelled build leaves in the world: the runner takes it down next. */
    @Override
    public Task cleanupAfterCancel(TaskContext c) {
        return scaffold == null || scaffold.placed().isEmpty()
                ? null
                : new ScaffoldCleanupTask(scaffold.placed());
    }

    @Override
    public void stop(TaskContext c) {
        pillarTargetY = null;
        resetMovers(c);
        if (breaker != null) {
            breaker.stop(c);
        }
        if (approach != null) {
            approach.stop(c.companion());
        }
        if (takeTask != null) {
            takeTask.stop(c);
        }
        if (gatherTask != null) {
            gatherTask.stop(c);
        }
        if (scaffold != null) {
            scaffold.stop();
        }
        // Cancelling ends the build: a reload must not pick it up again.
        deleteInProgress(c);
    }

    /** Writes the in-progress file again when the scaffold has grown since the last write. */
    private void syncInProgress(TaskContext c) {
        if (!inProgressStarted || scaffold == null) {
            return;
        }
        if (scaffold.placed().size() == writtenScaffoldCount) {
            return;
        }
        writeInProgress(c);
    }

    /** Persists the build so a reload can resume it. Best effort: a disk problem must not abort the build. */
    private void writeInProgress(TaskContext c) {
        if (scaffold == null) {
            return;
        }
        try {
            BlueprintStore.forLevel(c.level())
                    .saveInProgress(id, blueprint, origin, rotation, clearSite, gather, scaffold.placed());
            inProgressStarted = true;
            writtenScaffoldCount = scaffold.placed().size();
        } catch (RuntimeException ignored) {
            // The build continues; only its ability to survive a reload is lost.
        }
    }

    private void deleteInProgress(TaskContext c) {
        inProgressStarted = false;
        BlueprintStore.forLevel(c.level()).deleteInProgress(id);
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
