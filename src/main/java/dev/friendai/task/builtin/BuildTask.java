package dev.friendai.task.builtin;

import dev.friendai.build.BlockSpec;
import dev.friendai.build.Blueprint;
import dev.friendai.build.BuildOrder;
import dev.friendai.build.PartKind;
import dev.friendai.build.StructureParts;
import dev.friendai.creative.CreativeAction;
import dev.friendai.debug.DebugAction;
import dev.friendai.debug.DebugTarget;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.pathing.Pos;
import dev.friendai.place.Place;
import dev.friendai.place.PlaceCandidate;
import dev.friendai.place.PlaceKind;
import dev.friendai.place.PlaceNames;
import dev.friendai.place.PlaceSource;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.autonomy.ProtectionText;
import dev.friendai.debug.WorkCell;
import dev.friendai.debug.WorkKind;
import dev.friendai.debug.WorkSet;
import dev.friendai.debug.WorkStatus;
import dev.friendai.task.build.BlueprintStore;
import dev.friendai.task.build.Materials;
import dev.friendai.task.build.Parts;
import dev.friendai.task.build.Placement;
import dev.friendai.task.build.Reach;
import dev.friendai.task.build.Scaffold;
import dev.friendai.task.build.ScaffoldCleanupTask;
import dev.friendai.task.build.Site;
import dev.friendai.task.build.SupportNeeds;
import dev.friendai.task.interact.ContainerAccess;
import dev.friendai.task.interact.PlaceSpot;
import dev.friendai.task.inventory.CreativeItemLease;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import dev.friendai.task.mining.BlockBreaker;
import dev.friendai.task.nav.Approach;
import dev.friendai.task.nav.MoveHelper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
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
    private static final Direction[] PILLAR_SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    /** The sides a temporary support is tried on, below first so a block with no support need keeps today's shape. */
    private static final Direction[] SUPPORT_SIDES = {
        Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP
    };
    /** The production way to obtain a missing material; tests replace it to tighten the obtain limits. */
    private static final ObtainFactory DEFAULT_OBTAIN = (item, count) -> new ObtainTask(item, count, true, true);

    /** Creates the task that obtains one missing material for a build. */
    @FunctionalInterface
    public interface ObtainFactory {
        Task create(String item, int count);
    }

    private enum Phase {
        CHECK,
        TAKE,
        CLEAR,
        FOUNDATION,
        PLACE,
        CLEANUP
    }

    private final Blueprint blueprint;
    /** The selected parts; an empty set means the whole blueprint. */
    private final Set<PartKind> parts;
    /** The blueprint scoped to the selected parts; the full blueprint before the check. */
    private Blueprint buildPlan;
    /** The part analysis of the full blueprint, filled at CHECK for progress and the result line. */
    private StructureParts structure;

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
    /** The dimension the site is in; a finished build is remembered there. */
    private String dimension = "";

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
    /** Temporary-support cells already tried for a deferred block, so one refusal is not retried every pass. */
    private final Set<BlockPos> triedSupports = new HashSet<>();
    /** Whether the current pass placed at least one block; a pass that places nothing is the last. */
    private boolean passProgress;

    private enum Column {
        ALIGNED,
        WALKING,
        NONE
    }

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

    /** Builds only the selected parts; an empty set builds the whole blueprint. */
    public BuildTask(
            Blueprint blueprint,
            BlockPos origin,
            Rotation rotation,
            boolean clearSite,
            boolean gather,
            Set<PartKind> parts) {
        this(blueprint, origin, rotation, clearSite, gather, DEFAULT_OBTAIN, newId(), List.of(), parts);
    }

    /** The full constructor, for tests that need tighter obtain limits through their own factory. */
    public BuildTask(
            Blueprint blueprint,
            BlockPos origin,
            Rotation rotation,
            boolean clearSite,
            boolean gather,
            ObtainFactory obtainFactory) {
        this(blueprint, origin, rotation, clearSite, gather, obtainFactory, newId(), List.of(), Set.of());
    }

    private BuildTask(
            Blueprint blueprint,
            BlockPos origin,
            Rotation rotation,
            boolean clearSite,
            boolean gather,
            ObtainFactory obtainFactory,
            String id,
            List<BlockPos> adoptedScaffold,
            Set<PartKind> parts) {
        this.blueprint = blueprint;
        this.buildPlan = blueprint;
        this.parts = parts == null ? Set.of() : Set.copyOf(parts);
        this.origin = origin;
        this.rotation = rotation;
        this.clearSite = clearSite;
        this.gather = gather;
        this.obtainFactory = obtainFactory;
        this.tickLimit = blueprint.blocks().size() * 60 + 12_000;
        this.id = id;
        this.adoptedScaffold = List.copyOf(adoptedScaffold);
    }

    /** Resumes a partial build, keeping the parts it was asked for. */
    public static BuildTask resume(
            Blueprint blueprint,
            BlockPos origin,
            Rotation rotation,
            boolean clearSite,
            boolean gather,
            List<BlockPos> scaffold,
            String id,
            Set<PartKind> parts) {
        return new BuildTask(blueprint, origin, rotation, clearSite, gather, DEFAULT_OBTAIN, id, scaffold, parts);
    }

    private static String partNames(Set<PartKind> parts) {
        StringBuilder names = new StringBuilder();
        for (PartKind kind : PartKind.values()) {
            if (!parts.contains(kind)) {
                continue;
            }
            if (names.length() > 0) {
                names.append('/');
            }
            names.append(kind.id());
        }
        return names.toString();
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
     * {@code build:<id>,<x>,<y>,<z>,<facing>,<clearSite>,<gather>[,<part>+<part>…]}. The blueprint and the scaffold
     * live in the in-progress file named by {@code <id>}, under the world's blueprint directory. The optional eighth
     * field keeps a partial build partial across a reload; an older seven-field save restores as the whole blueprint.
     */
    @Override
    public String saveData() {
        String data = "build:" + id + "," + origin.getX() + "," + origin.getY() + "," + origin.getZ() + ","
                + Placement.facingFor(rotation).getSerializedName() + "," + clearSite + "," + gather;
        if (parts.isEmpty()) {
            return data;
        }
        StringBuilder text = new StringBuilder(data).append(',');
        boolean first = true;
        for (PartKind kind : PartKind.values()) {
            if (!parts.contains(kind)) {
                continue;
            }
            if (!first) {
                text.append('+');
            }
            text.append(kind.id());
            first = false;
        }
        return text.toString();
    }

    @Override
    public void start(TaskContext c) {
        dimension = c.level().dimension().identifier().toString();
    }

    /** Free time never builds over a place the owner already has; the footprint is boxed for any rotation. */
    private boolean overlapsPlaceInFreeTime(TaskContext c) {
        if (!c.autonomous() || !dev.friendai.task.autonomy.PlaceGuard.enabled(c.companion())) {
            return false;
        }
        int side = Math.max(blueprint.width(), blueprint.depth());
        return dev.friendai.task.autonomy.PlaceGuard.overlapsAnyPlace(
                c.companion(), origin.offset(-side, 0, -side), origin.offset(side, blueprint.height(), side));
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        TaskStatus status;
        if (phase != Phase.CLEANUP && ++ticks > tickLimit) {
            status = fail(c, "timed out");
        } else if (phase == Phase.CHECK && overlapsPlaceInFreeTime(c)) {
            status = fail(c, "the site overlaps a known place");
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
        structure = StructureParts.analyse(blueprint);
        buildPlan = parts.isEmpty()
                ? blueprint
                : new Blueprint(
                        blueprint.name(),
                        blueprint.width(),
                        blueprint.height(),
                        blueprint.depth(),
                        structure.cells(parts));
        if (!parts.isEmpty() && buildPlan.blocks().isEmpty()) {
            return fail(c, "the blueprint has no " + partNames(parts) + "; omit parts to build all of it");
        }
        placed = Placement.at(buildPlan, origin, rotation);
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
        // Every cell of the blueprint is off-limits to the scaffold, including the parts this run does not build.
        plannedCells = plannedCells(Placement.at(blueprint, origin, rotation).specs());
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
        order = BuildOrder.order(buildPlan, supportLookup()::get, origin.getX(), origin.getZ());
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

    /**
     * What each planned block needs first, mapped back from world cells to blueprint cells: the orderer asks this
     * for every spec and places a support before the blocks that use it. {@link SupportNeeds} owns the vanilla rules;
     * a state whose family is unknown answers no dependency and keeps the runtime deferral.
     */
    private Map<BlockSpec, BuildOrder.Support> supportLookup() {
        Map<BlockPos, BlockSpec> byWorld = new HashMap<>();
        for (BlockSpec spec : buildPlan.blocks()) {
            byWorld.put(worldPos(spec), spec);
        }
        Map<BlockSpec, BuildOrder.Support> lookup = new HashMap<>();
        for (BlockSpec spec : buildPlan.blocks()) {
            BlockPos pos = worldPos(spec);
            BlockState wanted = Placement.parseState(spec.state()).rotate(rotation);
            BlockPos support = SupportNeeds.supportOf(wanted, pos);
            if (support == null) {
                lookup.put(spec, BuildOrder.Support.NONE);
                continue;
            }
            BlockSpec cell = byWorld.get(support);
            lookup.put(
                    spec,
                    cell == null || Placement.parseState(cell.state()).isAir()
                            ? BuildOrder.Support.needed()
                            : BuildOrder.Support.on(cell));
        }
        return lookup;
    }

    private BlockPos worldPos(BlockSpec spec) {
        return origin.offset(Placement.rotate(
                new BlockPos(spec.x(), spec.y(), spec.z()), rotation, blueprint.width(), blueprint.depth()));
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
        if (c.canUseCreativeAction(CreativeAction.FLIGHT)) {
            return null;
        }
        if (scaffold.canScaffold(c)) {
            return null;
        }
        BlockPos pos = Reach.firstOutOfReach(c.level(), remaining, origin);
        if (pos == null) {
            return null;
        }
        return coords(pos) + " is out of reach (build height limit without scaffolding is " + Reach.HEIGHT
                + " above standing ground)";
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
        if (c.canUseCreativeAction(CreativeAction.FREE_MATERIALS)) {
            return Map.of();
        }
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
                approach = new Approach(List.of(spec.pos()), false, plannedCells);
            }
            switch (approach.tick(c)) {
                case MOVING -> {
                    return TaskStatus.RUNNING;
                }
                case UNREACHABLE -> {
                    return fail(c, "can't reach " + coords(spec.pos()));
                }
                case IN_REACH -> {}
            }
            Optional<PlaceSpot.Spot> spot = spot(c.level(), spec.pos(), spec.wanted());
            if (spot.isEmpty()) {
                return fail(
                        c,
                        "can't place " + describeState(spec.wanted()) + " at " + coords(spec.pos())
                                + ": nothing to place it against");
            }
            CreativeItemLease lease = takeOne(c, spec.wanted());
            if (lease == null) {
                return fail(c, "missing materials: 1 × " + itemId(Materials.itemFor(spec.wanted())));
            }
            lease.place(c.companion().hands(), spot.get().against(), spot.get().face());
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
            switch (scaffold.pillarTo(c, pillarTargetY)) {
                case CLIMBING -> {
                    return TaskStatus.RUNNING;
                }
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
                if (c.canUseCreativeAction(CreativeAction.FLIGHT)) {
                    if (approach == null) {
                        approach = new Approach(List.of(currentPos), false, plannedCells);
                    }
                    Approach.State state = approach.tick(c);
                    if (state == Approach.State.MOVING) {
                        return TaskStatus.RUNNING;
                    }
                    approach = null;
                    if (state == Approach.State.IN_REACH && inReach(c.companion(), currentPos)) {
                        continue;
                    }
                    return fail(c, "can't reach " + coords(currentPos));
                }
                int feetY = pillarFeetY(c.companion(), currentPos);
                if (feetY < 0) {
                    if (approach == null) {
                        approach = new Approach(currentPos);
                    }
                    Approach.State state = approach.tick(c);
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
                return fail(c, coords(currentPos) + " is " + ProtectionText.describe(c, currentPos));
            }
            Optional<PlaceSpot.Spot> spot = spot(level, currentPos, wanted.wanted());
            if (spot.isEmpty()) {
                // A block without a neighbour waits to the end of the queue: a neighbour placed later in the same
                // layer is better than scaffold material, and support is only placed once nothing else can help.
                deferred.add(current);
                current = null;
                resetMovers(c);
                approach = null;
                continue;
            }
            CreativeItemLease lease = takeOne(c, wanted.wanted());
            if (lease == null) {
                return fail(c, "missing materials: 1 × " + itemId(Materials.itemFor(wanted.wanted())));
            }
            lease.place(c.companion().hands(), spot.get().against(), spot.get().face());
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
     * What to do with the first block that still has no face after every other block had its chance. A block whose
     * state needs a solid neighbour to survive ({@link SupportNeeds}) can only be built on that cell: a temporary
     * support would be removed again and the block would drop, so the task names the missing cell instead. A block
     * that stands on its own is missing a face to click, not a support: one temporary block goes on the free
     * neighbour with the most open sides, the block is placed against it, and the scaffold is removed at the end.
     * A cell the blueprint fills itself, or one taken by a foreign block, is never used for support.
     */
    private TaskStatus supportStuck(TaskContext c) {
        Placement.PlacedSpec spec = specOf(deferred.get(0));
        BlockPos pos = spec.pos();
        BlockPos needed = SupportNeeds.supportOf(spec.wanted(), pos);
        if (needed != null) {
            return fail(
                    c,
                    "can't place " + describeState(spec.wanted()) + " at " + coords(pos)
                            + ": it needs a block at " + coords(needed)
                            + (plannedCells.contains(needed)
                                    ? " that the build has not placed"
                                    : " that the blueprint does not provide"));
        }
        String refused = "";
        for (BlockPos cell : supportCells(c.level(), pos)) {
            if (!triedSupports.add(cell)) {
                continue;
            }
            if (plannedCells.contains(cell) || taken(c.level(), cell, spec.wanted())) {
                refused = coords(cell);
                continue;
            }
            if (!scaffold.support(c, cell, pos)) {
                if (scaffold.outOfMaterial()) {
                    return fail(c, scaffold.failure());
                }
                refused = coords(cell);
                continue;
            }
            if (spot(c.level(), pos, spec.wanted()).isEmpty()) {
                continue;
            }
            queue = deferred;
            deferred = new ArrayList<>();
            cursor = 0;
            passProgress = false;
            return TaskStatus.RUNNING;
        }
        return fail(
                c,
                "can't place " + describeState(spec.wanted()) + " at " + coords(pos)
                        + (refused.isEmpty()
                                ? ": nothing to place it against"
                                : ": nothing to place it against, a temporary support at " + refused + " was refused"));
    }

    /**
     * Cells beside {@code pos} that may take a temporary support, the most open first so a block is built where it
     * has the most room. A cell is only usable when a scaffold block can itself be placed there, which needs a face
     * to click; below comes first on a tie, as it did when the support was always {@code pos.below()}.
     */
    private static List<BlockPos> supportCells(ServerLevel level, BlockPos pos) {
        List<BlockPos> cells = new ArrayList<>(SUPPORT_SIDES.length);
        for (Direction side : SUPPORT_SIDES) {
            BlockPos cell = pos.relative(side);
            BlockState there = level.getBlockState(cell);
            if (!there.canBeReplaced() || !there.getFluidState().isEmpty()) {
                continue;
            }
            if (PlaceSpot.spotAt(level, cell).isEmpty()) {
                continue;
            }
            cells.add(cell);
        }
        cells.sort(Comparator.comparingInt((BlockPos cell) -> -openSides(level, cell)));
        return cells;
    }

    /** How many of the six sides of {@code cell} are free: the most open neighbour is the easiest to build on. */
    private static int openSides(ServerLevel level, BlockPos cell) {
        int open = 0;
        for (Direction side : Direction.values()) {
            BlockState there = level.getBlockState(cell.relative(side));
            if (there.canBeReplaced() && there.getFluidState().isEmpty()) {
                open++;
            }
        }
        return open;
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
            BlockPos column =
                    new BlockPos(target.getX() + side.getStepX(), base.getY(), target.getZ() + side.getStepZ());
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
    private static Optional<PlaceSpot.Spot> spot(ServerLevel level, BlockPos pos, BlockState wanted) {
        BlockPos support = SupportNeeds.supportOf(wanted, pos);
        if (support != null) {
            // A wall torch clicked against the floor becomes a standing torch; only the cell it survives on is right.
            if (!wanted.canSurvive(level, pos)) {
                return Optional.empty();
            }
            Direction face = faceBetween(support, pos);
            return face == null ? Optional.empty() : Optional.of(new PlaceSpot.Spot(pos, support, face));
        }
        Optional<PlaceSpot.Spot> strict = PlaceSpot.spotAt(level, pos);
        return strict.isPresent() ? strict : PlaceSpot.standingSpotAt(level, pos);
    }

    private static Direction faceBetween(BlockPos from, BlockPos to) {
        int dx = Integer.signum(to.getX() - from.getX());
        int dy = Integer.signum(to.getY() - from.getY());
        int dz = Integer.signum(to.getZ() - from.getZ());
        if (dx != 0) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        if (dy != 0) {
            return dy > 0 ? Direction.UP : Direction.DOWN;
        }
        if (dz != 0) {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        return null;
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
            asideTarget = c.canUseCreativeAction(CreativeAction.FLIGHT)
                    ? freeFlightSpotNear(c, pos)
                    : freeSpotNear(c.level(), companion, pos);
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
        if (state == MoveHelper.State.ARRIVED) {
            asideMover.stopAtDestination(c);
        } else {
            asideMover.reset(c);
        }
        asideTarget = null;
        return state == MoveHelper.State.ARRIVED ? TaskStatus.RUNNING : TaskStatus.FAILED;
    }

    /** Finds a collision-free airborne spot so creative building can clear its own placement cell. */
    private BlockPos freeFlightSpotNear(TaskContext c, BlockPos target) {
        CompanionEntity companion = c.companion();
        ServerLevel level = c.level();
        int feetY = companion.blockPosition().getY();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        Vec3 targetCenter = Vec3.atCenterOf(target);
        for (int dy = -3; dy <= 3; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    double horizontal = Math.sqrt(dx * dx + dz * dz);
                    int candidateY = feetY + dy;
                    boolean clearVerticalSeparation = Math.abs(candidateY - target.getY()) >= 2;
                    if (horizontal > 3.0 || horizontal < 1.5 && !clearVerticalSeparation) {
                        continue;
                    }
                    BlockPos candidate = new BlockPos(target.getX() + dx, candidateY, target.getZ() + dz);
                    if (plannedCells.contains(candidate)
                            || plannedCells.contains(candidate.above())
                            || plannedCells.contains(candidate.above(2))) {
                        continue;
                    }
                    Vec3 feet = Vec3.atBottomCenterOf(candidate);
                    if (feet.add(0.0, companion.getEyeHeight(), 0.0).distanceTo(targetCenter) > Approach.REACH) {
                        continue;
                    }
                    AABB box = companion.getBoundingBox().move(feet.subtract(companion.position()));
                    if (box.minY < level.getMinY()
                            || box.maxY > level.getMaxY() + 1.0
                            || !flightCellLoaded(level, box)
                            || !level.noCollision(companion, box)) {
                        continue;
                    }
                    double distance = companion.position().distanceToSqr(feet);
                    if (distance < bestDistance) {
                        best = candidate;
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    private static boolean flightCellLoaded(ServerLevel level, AABB box) {
        int minX = BlockPos.containing(box.minX, box.minY, box.minZ).getX();
        int maxX = BlockPos.containing(box.maxX, box.minY, box.maxZ).getX();
        int minZ = BlockPos.containing(box.minX, box.minY, box.minZ).getZ();
        int maxZ = BlockPos.containing(box.maxX, box.minY, box.maxZ).getZ();
        return level.hasChunkAt(minX, minZ)
                && level.hasChunkAt(minX, maxZ)
                && level.hasChunkAt(maxX, minZ)
                && level.hasChunkAt(maxX, maxZ);
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
            c.runner()
                    .notice("building " + blueprint.name() + ": " + nextNotice + "% (" + done + "/" + total
                            + " blocks)");
            nextNotice += PROGRESS_STEP;
        }
    }

    private TaskStatus finish(TaskContext c) {
        String selected = parts.isEmpty() || structure == null ? "" : " (" + structure.describe(parts) + ")";
        summary = "built " + blueprint.name() + " at " + coords(origin) + " facing "
                + Placement.facingFor(rotation).getSerializedName() + ": " + placedCount + " placed" + selected
                + ", " + clearedCount + " cleared, " + foundationCount + " foundation";
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
                summary += "; left " + scaffold.left() + " scaffold blocks behind"
                        + (scaffold.failure().isBlank() ? "" : " (" + scaffold.failure() + ")");
                return TaskStatus.FAILED;
            }
        }
        return TaskStatus.RUNNING;
    }

    private CreativeItemLease takeOne(TaskContext c, BlockState wanted) {
        Item item = Materials.itemFor(wanted);
        ItemMatcher matcher = ItemMatcher.parse(itemId(item)).orElse(null);
        if (matcher != null) {
            List<ItemStack> removed = InventoryOps.remove(c.companion().inventory(), matcher, 1);
            if (!removed.isEmpty()) {
                scaffold.spend(item);
                return CreativeItemLease.carried(removed.get(0));
            }
        }
        if (c.companion().getMainHandItem().is(item)) {
            ItemStack held = c.companion().getMainHandItem().split(1);
            if (!held.isEmpty()) {
                scaffold.spend(item);
                return CreativeItemLease.carried(held);
            }
        }
        return c.canUseCreativeAction(CreativeAction.FREE_MATERIALS)
                ? CreativeItemLease.synthetic(new ItemStack(item))
                : null;
    }

    private TaskStatus fail(TaskContext c, String text) {
        summary = text;
        return complete(c, TaskStatus.FAILED);
    }

    /** Leftover scaffold is the one thing a cancelled build leaves in the world: the runner takes it down next. */
    @Override
    public Task cleanupAfterCancel(TaskContext c) {
        return scaffold == null || scaffold.placed().isEmpty() ? null : new ScaffoldCleanupTask(scaffold.placed());
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
        String part = "";
        if (structure != null && current != null) {
            PartKind role = structure.roleOf(current);
            if (role != null && role != PartKind.OTHER) {
                part = ", " + role.id();
            }
        }
        return "building " + blueprint.name() + part + ": " + done + "/" + total + " blocks at " + coords(origin);
    }

    @Override
    public List<DebugTarget> debugTargets() {
        BlockPos pos = null;
        String detail = "Place build block";
        if (phase == Phase.CLEAR) {
            pos = breaker == null ? clearQueue.peek() : breaker.target();
            detail = "Clear build site";
        } else if (phase == Phase.FOUNDATION && foundationIndex < foundation.size()) {
            pos = foundation.get(foundationIndex).pos();
            detail = "Build foundation";
        } else if (phase == Phase.PLACE) {
            pos = currentPos;
        } else if (phase == Phase.TAKE && takeTarget != null) {
            pos = takeTarget;
            detail = "Take materials from storage";
        }
        if (pos == null) {
            return List.of();
        }
        return List.of(new DebugTarget(
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                phase == Phase.CLEAR ? DebugAction.BREAK : phase == Phase.TAKE ? DebugAction.USE : DebugAction.PLACE,
                detail));
    }

    @Override
    public List<WorkSet> debugWorkSets() {
        if (placed == null || placed.specs().isEmpty()) return List.of();
        List<WorkCell> cells = new ArrayList<>();
        for (int i = 0; i < placed.specs().size(); i++) {
            Placement.PlacedSpec placedSpec = placed.specs().get(i);
            BlockSpec spec = buildPlan.blocks().get(i);
            int index = order.indexOf(spec);
            boolean deferredPending = deferred.contains(spec);
            WorkStatus status;
            if (index < 0) status = WorkStatus.DONE;
            else if (current != null && current.equals(spec)) status = WorkStatus.NEXT;
            else if (index < cursor && !deferredPending) status = WorkStatus.DONE;
            else status = WorkStatus.PENDING;
            cells.add(new WorkCell(placedSpec.pos().getX(), placedSpec.pos().getY(), placedSpec.pos().getZ(),
                    DebugAction.PLACE, status, "Place build block"));
        }
        int doneCount = (int) cells.stream().filter(cell -> cell.status() == WorkStatus.DONE).count();
        int nextCount = (int) cells.stream().filter(cell -> cell.status() == WorkStatus.NEXT).count();
        int pendingCount = (int) cells.stream().filter(cell -> cell.status() == WorkStatus.PENDING).count();
        int totalCount = cells.size();
        return List.of(new WorkSet(blueprint.name(), WorkKind.BUILD, totalCount, doneCount, nextCount, pendingCount,
                0, false, cells));
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    /** The finished building, named after its blueprint; a "wheat_farm" blueprint becomes a farm, not a house. */
    @Override
    public PlaceCandidate placeCandidate() {
        if (dimension.isBlank()) {
            return null;
        }
        String label = PlaceNames.humanize(blueprint.name());
        int span = Math.max(blueprint.width(), blueprint.depth()) / 2 + 2;
        return new PlaceCandidate(
                PlaceKind.inferFromName(blueprint.name()),
                label,
                label,
                dimension,
                new Pos(origin.getX(), origin.getY(), origin.getZ()),
                Math.clamp(span, 1, Place.RADIUS_MAX),
                PlaceSource.BUILD);
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
