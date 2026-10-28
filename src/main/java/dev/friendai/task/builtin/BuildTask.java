package dev.friendai.task.builtin;

import dev.friendai.debug.DebugAction;
import dev.friendai.debug.DebugTarget;
import dev.friendai.debug.WorkCell;
import dev.friendai.debug.WorkKind;
import dev.friendai.debug.WorkSet;
import dev.friendai.debug.WorkStatus;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.construction.blueprint.BlockSpec;
import dev.friendai.features.construction.blueprint.Blueprint;
import dev.friendai.features.construction.blueprint.BuildOrder;
import dev.friendai.features.construction.blueprint.PartKind;
import dev.friendai.features.construction.blueprint.PlacementRefusal;
import dev.friendai.features.construction.blueprint.StanceOrder;
import dev.friendai.features.construction.blueprint.StructureParts;
import dev.friendai.features.creative.CreativeAction;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.features.places.Place;
import dev.friendai.features.places.PlaceCandidate;
import dev.friendai.features.places.PlaceKind;
import dev.friendai.features.places.PlaceNames;
import dev.friendai.features.places.PlaceSource;
import dev.friendai.placed.PlacedService;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.autonomy.PlaceGuard;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
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
    /** How many stance attempts one cell may cost before the build names it as the cause. */
    private static final int MAX_SELF_ATTEMPTS = 6;
    /** How many passes an entity in the cell is waited out before the build names it. */
    private static final int MAX_ENTITY_ATTEMPTS = 3;
    /** How many ticks one wait for an entity lasts before the next pass. */
    private static final int ENTITY_WAIT_TICKS = 60;
    /** How many horizontal rings a stance search considers. */
    private static final int STANCE_RADIUS = 3;
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

    /** Cells this build may treat as its own: the adopted scaffold plus the owner's placement ledger in the site. */
    private Set<BlockPos> ownedCells = Set.of();
    /** Blueprint cells this run placed, so ownership is known before the ledger's next write. */
    private final Set<BlockPos> ownPlaced = new HashSet<>();
    /** Stances that were free but could not be reached; never tried again for this build. */
    private final Set<BlockPos> rejectedStances = new HashSet<>();
    /** Bounded attempts per cell, shared by stance, entity and support recovery. */
    private final Map<BlockPos, Integer> attempts = new HashMap<>();
    /** The stance chosen for the current column; kept while it still serves the next cell. */
    private BlockPos stance;

    private BlockPos waitCell;
    private int waitTicks;
    private int selfRecoveries;
    /** The last typed refusal, for {@link #debugStatus()} and the result summary. */
    private PlacementRefusal refusal;
    /** The own block currently being taken back, if any. */
    private BlockBreaker takeBack;
    /** Whether the build finished well; a stop after a success must not leave a resume record. */
    private boolean succeeded;

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
     * {@code <id>,<x>,<y>,<z>,<facing>,<clearSite>,<gather>[,<part>+<part>…]}. The blueprint and the scaffold live
     * in the in-progress file named by {@code <id>}, under the world's blueprint directory.
     */
    @Override
    public String saveData() {
        String data = id + "," + origin.getX() + "," + origin.getY() + "," + origin.getZ() + ","
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
    private Place overlappingPlace(TaskContext c) {
        if (!c.autonomous() || !PlaceGuard.enabled(c.companion())) {
            return null;
        }
        int side = Math.max(blueprint.width(), blueprint.depth());
        return PlaceGuard.overlapping(
                c.companion(), origin.offset(-side, 0, -side), origin.offset(side, blueprint.height(), side));
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        TaskStatus status;
        if (phase != Phase.CLEANUP && ++ticks > tickLimit) {
            status = fail(c, new PlacementRefusal.AttemptsExhausted(toPos(origin), "timed out", ticks), true);
        } else if (phase == Phase.CHECK && overlappingPlace(c) != null) {
            status = fail(c, protectedRefusal(toPos(origin), overlappingPlace(c)), true);
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
        } else if (status == TaskStatus.SUCCESS) {
            succeeded = true;
            deleteInProgress(c);
        } else {
            // A failed build is resumable at its origin instead of being re-sited by the next tool call.
            persistForResume(c);
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
            return reject(c, "the blueprint has no " + partNames(parts) + "; omit parts to build all of it");
        }
        placed = Placement.at(buildPlan, origin, rotation);
        ownedCells = ownership(c);
        site = Site.check(level, placed, c.companion(), clearSite, ownedCells::contains);
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
        PlacementRefusal unreachable = unreachable(c, remaining);
        if (unreachable != null) {
            return fail(c, unreachable, true);
        }
        if (!site.refusals().isEmpty()) {
            return failAll(c, site.refusals());
        }
        if (materialError != null) {
            return fail(c, new PlacementRefusal.OutOfMaterial(toPos(origin), "scaffold", materialError, 0), true);
        }
        clearQueue.addAll(site.toClear());
        foundation = site.foundation().stream()
                .sorted(Comparator.comparingInt(spec -> spec.pos().getY()))
                .toList();
        order = BuildOrder.order(
                buildPlan,
                supportLookup()::get,
                c.companion().blockPosition().getX(),
                c.companion().blockPosition().getZ(),
                bodyCells(c.companion()));
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
    private PlacementRefusal unreachable(TaskContext c, List<Placement.PlacedSpec> remaining) {
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
        return new PlacementRefusal.OutOfReach(
                toPos(pos), "build height limit without scaffolding is " + Reach.HEIGHT + " above standing ground", 0);
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
                return fail(
                        c,
                        new PlacementRefusal.OutOfMaterial(toPos(origin), itemId(item), obtainSummary, attempt(origin)),
                        true);
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
        return fail(c, missingRefusal(missing), true);
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

    private PlacementRefusal missingRefusal(Map<Item, Integer> missing) {
        String first = "unknown";
        StringBuilder detail = new StringBuilder();
        for (Map.Entry<Item, Integer> entry : missing.entrySet()) {
            if (detail.length() == 0) {
                first = itemId(entry.getKey());
            } else {
                detail.append(", ");
            }
            detail.append(entry.getValue()).append(" × ").append(itemId(entry.getKey()));
        }
        return new PlacementRefusal.OutOfMaterial(toPos(origin), first, "need " + detail, attempt(origin));
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
                    return fail(
                            c,
                            new PlacementRefusal.OutOfReach(toPos(spec.pos()), "foundation", attempt(spec.pos())),
                            false);
                }
                case IN_REACH -> {}
            }
            Optional<PlaceSpot.Spot> spot = spot(c.level(), spec.pos(), spec.wanted());
            if (spot.isEmpty()) {
                return fail(
                        c,
                        new PlacementRefusal.NoFace(
                                toPos(spec.pos()), describeState(spec.wanted()), "", attempt(spec.pos())),
                        false);
            }
            CreativeItemLease lease = takeOne(c, spec.wanted());
            if (lease == null) {
                return fail(
                        c,
                        new PlacementRefusal.OutOfMaterial(
                                toPos(spec.pos()),
                                itemId(Materials.itemFor(spec.wanted())),
                                "foundation",
                                attempt(spec.pos())),
                        false);
            }
            lease.place(c.companion().hands(), spot.get().against(), spot.get().face());
            BlockState after = c.level().getBlockState(spec.pos());
            if (after.getBlock() == spec.wanted().getBlock()) {
                Parts.reconcile(c.level(), spec.pos(), spec.wanted());
                ownPlaced.add(spec.pos());
                foundationCount++;
                foundationIndex++;
                approach = null;
                return TaskStatus.RUNNING;
            }
            return fail(
                    c,
                    new PlacementRefusal.Rejected(
                            toPos(spec.pos()), describeState(spec.wanted()), blockId(after), attempt(spec.pos())),
                    false);
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
                    BlockPos cell = currentPos != null ? currentPos : origin;
                    pillarTargetY = null;
                    if (scaffold.outOfMaterial()) {
                        return fail(
                                c,
                                new PlacementRefusal.OutOfMaterial(
                                        toPos(cell), "dirt or cobblestone", scaffold.failure(), attempt(cell)),
                                false);
                    }
                    return fail(
                            c,
                            new PlacementRefusal.OutOfReach(
                                    toPos(cell), "scaffold: " + scaffold.failure(), attempt(cell)),
                            false);
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
                clearCurrent(c);
                advance(c);
                continue;
            }
            if (world.equals(wanted.wanted())) {
                clearCurrent(c);
                advance(c);
                continue;
            }
            // Our own block in another state: fixing the state is cheaper and safer than breaking our own work.
            if (ownsCell(currentPos) && world.getBlock() == wanted.wanted().getBlock()) {
                Parts.reconcile(level, currentPos, wanted.wanted());
                ownPlaced.add(currentPos);
                placedCount++;
                clearCurrent(c);
                advance(c);
                continue;
            }
            // Our own block that is not the planned one: take it back before filling the cell.
            if (ownsCell(currentPos) && !world.isAir() && !world.canBeReplaced()) {
                if (takeBack == null) {
                    takeBack = new BlockBreaker(currentPos, state -> ownsCell(currentPos), false, true);
                }
                switch (takeBack.tick(c)) {
                    case WORKING -> {
                        return TaskStatus.RUNNING;
                    }
                    case BROKEN -> {
                        PlacedService.forget(c.companion(), currentPos);
                        ownedCells.remove(currentPos);
                        ownPlaced.remove(currentPos);
                        takeBack = null;
                        selfRecoveries++;
                        return TaskStatus.RUNNING;
                    }
                    case GONE -> {
                        ownedCells.remove(currentPos);
                        takeBack = null;
                        return TaskStatus.RUNNING;
                    }
                    default -> {
                        takeBack = null;
                        return fail(
                                c,
                                new PlacementRefusal.OwnBlock(
                                        toPos(currentPos), blockId(world), false, attempt(currentPos)),
                                false);
                    }
                }
            }
            PlacementRefusal blocked = classify(level, currentPos, wanted.wanted());
            if (blocked != null) {
                return fail(c, blocked, false);
            }
            LivingEntity other = otherEntityAt(c, currentPos);
            if (other != null && !standsIn(c.companion(), currentPos)) {
                int n = attempt(currentPos);
                if (n <= MAX_ENTITY_ATTEMPTS) {
                    deferCurrent(c);
                    continue;
                }
                return fail(c, new PlacementRefusal.OtherEntity(toPos(currentPos), entityId(other), n), false);
            }
            if (asideTarget != null || standsIn(c.companion(), currentPos)) {
                return stepAside(c, currentPos);
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
                    return fail(
                            c,
                            new PlacementRefusal.OutOfReach(toPos(currentPos), "flight", attempt(currentPos)),
                            false);
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
                    return fail(
                            c,
                            new PlacementRefusal.OutOfReach(toPos(currentPos), "no route", attempt(currentPos)),
                            false);
                }
                pillarTargetY = feetY;
                return TaskStatus.RUNNING;
            }
            c.companion().getNavigation().stop();
            if (!c.companion().hands().canInteract(currentPos)) {
                return fail(
                        c,
                        new PlacementRefusal.Protected(toPos(currentPos), "", "", 0, 0, 0, attempt(currentPos)),
                        false);
            }
            Place protectedPlace = PlaceGuard.protecting(c, currentPos);
            if (protectedPlace != null) {
                return fail(c, protectedRefusal(toPos(currentPos), protectedPlace), false);
            }
            Optional<PlaceSpot.Spot> spot = spot(level, currentPos, wanted.wanted());
            if (spot.isEmpty()) {
                // A block without a neighbour waits to the end of the queue: a neighbour placed later in the same
                // layer is better than scaffold material, and support is only placed once nothing else can help.
                deferCurrent(c);
                continue;
            }
            CreativeItemLease lease = takeOne(c, wanted.wanted());
            if (lease == null) {
                return fail(
                        c,
                        new PlacementRefusal.OutOfMaterial(
                                toPos(currentPos), itemId(Materials.itemFor(wanted.wanted())), "", attempt(currentPos)),
                        false);
            }
            lease.place(c.companion().hands(), spot.get().against(), spot.get().face());
            BlockState after = level.getBlockState(currentPos);
            if (after.getBlock() == wanted.wanted().getBlock()) {
                Parts.reconcile(level, currentPos, wanted.wanted());
                ownPlaced.add(currentPos);
                placedCount++;
                passProgress = true;
                clearCurrent(c);
                advance(c);
                return TaskStatus.RUNNING;
            }
            return fail(
                    c,
                    new PlacementRefusal.Rejected(
                            toPos(currentPos), describeState(wanted.wanted()), blockId(after), attempt(currentPos)),
                    false);
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
        LivingEntity entity = otherEntityAt(c, pos);
        if (entity != null) {
            if (waitTicks < ENTITY_WAIT_TICKS) {
                waitTicks++;
                return TaskStatus.RUNNING;
            }
            waitTicks = 0;
            int n = attempt(pos);
            if (n <= MAX_ENTITY_ATTEMPTS) {
                retryDeferredPass();
                return TaskStatus.RUNNING;
            }
            return fail(c, new PlacementRefusal.OtherEntity(toPos(pos), entityId(entity), n), false);
        }
        if (standsIn(c.companion(), pos)) {
            int n = attempt(pos);
            if (n <= MAX_SELF_ATTEMPTS) {
                retryDeferredPass();
                return TaskStatus.RUNNING;
            }
            return fail(c, new PlacementRefusal.SelfBody(toPos(pos), n), false);
        }
        BlockPos needed = SupportNeeds.supportOf(spec.wanted(), pos);
        if (needed != null) {
            return fail(
                    c,
                    new PlacementRefusal.NoSupport(
                            toPos(pos), toPos(needed), plannedCells.contains(needed), attempt(pos)),
                    false);
        }
        if (spot(c.level(), pos, spec.wanted()).isPresent()) {
            // The obstacle moved or the neighbouring block arrived while this cell waited.
            retryDeferredPass();
            return TaskStatus.RUNNING;
        }
        String refused = "";
        for (BlockPos cell : supportCells(c.level(), pos)) {
            if (!triedSupports.add(cell)) {
                continue;
            }
            if (plannedCells.contains(cell) || classify(c.level(), cell, spec.wanted()) != null) {
                refused = coords(cell);
                continue;
            }
            if (!scaffold.support(c, cell, pos)) {
                if (scaffold.outOfMaterial()) {
                    return fail(
                            c,
                            new PlacementRefusal.OutOfMaterial(
                                    toPos(pos), "dirt or cobblestone", scaffold.failure(), attempt(pos)),
                            false);
                }
                refused = coords(cell);
                continue;
            }
            if (spot(c.level(), pos, spec.wanted()).isEmpty()) {
                continue;
            }
            retryDeferredPass();
            return TaskStatus.RUNNING;
        }
        return fail(
                c,
                new PlacementRefusal.NoFace(
                        toPos(pos),
                        describeState(spec.wanted()),
                        refused.isEmpty() ? "" : "a temporary support at " + refused + " was refused",
                        attempt(pos)),
                false);
    }

    /** Starts another pass over the deferred cells, so a bounded retry can place what waited. */
    private void retryDeferredPass() {
        queue = deferred;
        deferred = new ArrayList<>();
        cursor = 0;
        passProgress = false;
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

    /** The typed cause when the cell is not clearable, or null when it can be filled. */
    private PlacementRefusal classify(ServerLevel level, BlockPos pos, BlockState wanted) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()
                || state.canBeReplaced()
                || !state.getFluidState().isEmpty()
                || state.equals(wanted)
                || state.getBlock() == wanted.getBlock()
                || Site.isNatural(state)) {
            return null;
        }
        int n = attempts.getOrDefault(pos, 0);
        return ownsCell(pos)
                ? new PlacementRefusal.OwnBlock(toPos(pos), blockId(state), false, n)
                : new PlacementRefusal.ForeignBlock(toPos(pos), blockId(state), n);
    }

    /** Whether the companion's body overlaps pos, which makes vanilla refuse the placement. */
    private static boolean standsIn(CompanionEntity companion, BlockPos pos) {
        return companion.getBoundingBox().intersects(new AABB(pos));
    }

    /**
     * Moves the companion out of a block it is about to fill. The stance is chosen once from a deterministic order
     * and kept while it serves the next cell; a candidate the mover cannot reach is rejected and the next one tried.
     * Only the bounded attempt budget turns this into a failure, and a failure names the cell.
     */
    private TaskStatus stepAside(TaskContext c, BlockPos pos) {
        CompanionEntity companion = c.companion();
        if (asideTarget == null) {
            if (!standsIn(companion, pos)) {
                return TaskStatus.RUNNING;
            }
            BlockPos candidate = nextStance(c, pos);
            if (candidate == null) {
                return deferOrNameSelf(c, pos);
            }
            asideTarget = candidate;
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
            BlockPos reached = asideTarget;
            asideTarget = null;
            if (standsIn(companion, pos)) {
                rejectedStances.add(reached);
                return deferOrNameSelf(c, pos);
            }
            stance = companion.blockPosition();
            selfRecoveries++;
            return TaskStatus.RUNNING;
        }
        asideMover.reset(c);
        rejectedStances.add(asideTarget);
        asideTarget = null;
        return deferOrNameSelf(c, pos);
    }

    /** Defers the current cell while attempts remain, otherwise names the companion's own body as the cause. */
    private TaskStatus deferOrNameSelf(TaskContext c, BlockPos pos) {
        int n = attempt(pos);
        if (n <= MAX_SELF_ATTEMPTS) {
            deferCurrent(c);
            return TaskStatus.RUNNING;
        }
        return fail(c, new PlacementRefusal.SelfBody(toPos(pos), n), false);
    }

    /** The stance to try next: the kept one when it still serves the cell, otherwise the first usable candidate. */
    private BlockPos nextStance(TaskContext c, BlockPos pos) {
        if (stance != null && stanceUsable(c, stance, pos)) {
            return stance;
        }
        stance = null;
        Pos target = toPos(pos);
        Pos feet = toPos(c.companion().blockPosition());
        for (Pos candidate : StanceOrder.candidates(target, feet, STANCE_RADIUS)) {
            BlockPos cell = new BlockPos(candidate.x(), candidate.y(), candidate.z());
            if (rejectedStances.contains(cell) || !stanceUsable(c, cell, pos)) {
                continue;
            }
            return cell;
        }
        return null;
    }

    /** Whether the companion can stand at {@code candidate} and still click the target from there. */
    private boolean stanceUsable(TaskContext c, BlockPos candidate, BlockPos target) {
        CompanionEntity companion = c.companion();
        ServerLevel level = c.level();
        if (!level.hasChunkAt(candidate)) {
            return false;
        }
        boolean flying = c.canUseCreativeAction(CreativeAction.FLIGHT);
        if (!flying && !freeStanding(level, candidate)) {
            return false;
        }
        AABB body =
                companion.getBoundingBox().move(Vec3.atBottomCenterOf(candidate).subtract(companion.position()));
        if (body.minY < level.getMinY() || body.maxY > level.getMaxY() + 1.0) {
            return false;
        }
        if (body.intersects(new AABB(target)) || !level.noCollision(companion, body)) {
            return false;
        }
        Vec3 eye = Vec3.atBottomCenterOf(candidate).add(0.0, companion.getEyeHeight(), 0.0);
        return eye.distanceTo(Vec3.atCenterOf(target)) <= Approach.REACH;
    }

    /** A living entity other than the companion in the cell, or null. Players count; only the builder is exempt. */
    private static LivingEntity otherEntityAt(TaskContext c, BlockPos pos) {
        ServerLevel level = c.level();
        if (!level.hasChunkAt(pos)) {
            return null;
        }
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, new AABB(pos))) {
            if (entity.isAlive() && entity != c.companion()) {
                return entity;
            }
        }
        return null;
    }

    private static String entityId(LivingEntity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    private static boolean freeStanding(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).isAir()
                && level.getBlockState(pos.above()).isAir()
                && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
    }

    /** The cells the companion's body covers when the order is computed, so the build walks around it. */
    private static Set<Pos> bodyCells(CompanionEntity companion) {
        Set<Pos> cells = new HashSet<>();
        AABB box = companion.getBoundingBox();
        int minX = (int) Math.floor(box.minX);
        int maxX = (int) Math.floor(box.maxX - 1.0E-4);
        int minY = (int) Math.floor(box.minY);
        int maxY = (int) Math.floor(box.maxY - 1.0E-4);
        int minZ = (int) Math.floor(box.minZ);
        int maxZ = (int) Math.floor(box.maxZ - 1.0E-4);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cells.add(new Pos(x, y, z));
                }
            }
        }
        return cells;
    }

    /** The scaffold this build adopted, plus every ledger placement inside the site. */
    private Set<BlockPos> ownership(TaskContext c) {
        Set<BlockPos> owned = new HashSet<>(Site.ownedByCompanion(c.companion(), placed));
        owned.addAll(scaffold.placed());
        return owned;
    }

    private boolean ownsCell(BlockPos pos) {
        return ownPlaced.contains(pos)
                || ownedCells.contains(pos)
                || (scaffold != null && scaffold.placed().contains(pos));
    }

    private int attempt(BlockPos pos) {
        return attempts.merge(pos, 1, Integer::sum);
    }

    private void clearCurrent(TaskContext c) {
        current = null;
        resetMovers(c);
        approach = null;
    }

    private void deferCurrent(TaskContext c) {
        if (current != null) {
            deferred.add(current);
        }
        clearCurrent(c);
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
            c.execution()
                    .notice("building " + blueprint.name() + ": " + nextNotice + "% (" + done + "/" + total
                            + " blocks)");
            nextNotice += PROGRESS_STEP;
        }
    }

    private TaskStatus finish(TaskContext c) {
        String selected = parts.isEmpty() || structure == null ? "" : " (" + structure.describe(parts) + ")";
        summary = "built " + blueprint.name() + " at " + coords(origin) + " facing "
                + Placement.facingFor(rotation).getSerializedName() + ": " + placedCount + " placed" + selected
                + ", " + clearedCount + " cleared, " + foundationCount + " foundation" + recoverySuffix();
        return complete(c, TaskStatus.SUCCESS);
    }

    private String recoverySuffix() {
        return selfRecoveries == 0 ? "" : ", " + selfRecoveries + " self-blocked recoveries";
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

    private TaskStatus fail(TaskContext c, PlacementRefusal refusal, boolean siteFailure) {
        this.refusal = refusal;
        // A site check happens before any work, so the owner gets the bare cause; a placement refusal says what to do
        // next as well, because the build is already half done.
        summary = refusal.describe() + (siteFailure ? "" : "; " + refusal.remedy()) + resumeHint(c) + recoverySuffix();
        return complete(c, TaskStatus.FAILED);
    }

    private TaskStatus failAll(TaskContext c, List<PlacementRefusal> refusals) {
        if (refusals.isEmpty()) {
            return reject(c, "the site cannot be used");
        }
        this.refusal = refusals.getFirst();
        StringBuilder text = new StringBuilder();
        for (PlacementRefusal refusal : refusals) {
            if (text.length() > 0) {
                text.append("; ");
            }
            text.append(refusal.describe());
        }
        summary = text + resumeHint(c) + recoverySuffix();
        return complete(c, TaskStatus.FAILED);
    }

    /** A failure that is not a placement decision: a configuration problem or a cleanup result. */
    private TaskStatus reject(TaskContext c, String text) {
        summary = text + recoverySuffix();
        return complete(c, TaskStatus.FAILED);
    }

    /** Tells the model where to resume, but only when there is something there to resume. */
    private String resumeHint(TaskContext c) {
        if (placed == null || !hasProgress(c.level())) {
            return "";
        }
        return "; half built at " + coords(origin) + " — resume it with build_structure at the same origin";
    }

    private boolean hasProgress(ServerLevel level) {
        if (placedCount > 0 || clearedCount > 0 || foundationCount > 0) {
            return true;
        }
        if (scaffold != null && !scaffold.placed().isEmpty()) {
            return true;
        }
        for (Placement.PlacedSpec spec : placed.specs()) {
            if (level.getBlockState(spec.pos()).equals(spec.wanted())) {
                return true;
            }
        }
        return false;
    }

    private void persistForResume(TaskContext c) {
        if (scaffold == null || placed == null) {
            return;
        }
        writeInProgress(c);
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
        if (takeBack != null) {
            takeBack.stop(c);
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
        if (succeeded) {
            deleteInProgress(c);
        } else {
            // A cancellation is still a site with work done: keep it resumable at the same origin.
            persistForResume(c);
        }
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
        if (scaffold == null || placed == null) {
            return;
        }
        try {
            List<BlockPos> remaining = new ArrayList<>();
            for (Placement.PlacedSpec spec : placed.specs()) {
                if (!c.level().getBlockState(spec.pos()).equals(spec.wanted())) {
                    remaining.add(spec.pos());
                }
            }
            String owner = c.companion().ownerUuid() == null
                    ? ""
                    : c.companion().ownerUuid().toString();
            BlueprintStore.forLevel(c.level())
                    .saveInProgress(
                            id,
                            blueprint,
                            origin,
                            rotation,
                            clearSite,
                            gather,
                            scaffold.placed(),
                            parts,
                            remaining,
                            owner,
                            dimension);
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
            cells.add(new WorkCell(
                    placedSpec.pos().getX(),
                    placedSpec.pos().getY(),
                    placedSpec.pos().getZ(),
                    DebugAction.PLACE,
                    status,
                    "Place build block"));
        }
        int doneCount = (int)
                cells.stream().filter(cell -> cell.status() == WorkStatus.DONE).count();
        int nextCount = (int)
                cells.stream().filter(cell -> cell.status() == WorkStatus.NEXT).count();
        int pendingCount = (int) cells.stream()
                .filter(cell -> cell.status() == WorkStatus.PENDING)
                .count();
        int totalCount = cells.size();
        return List.of(new WorkSet(
                blueprint.name(), WorkKind.BUILD, totalCount, doneCount, nextCount, pendingCount, 0, false, cells));
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    @Override
    public String debugStatus() {
        String cell = currentPos == null ? "-" : coords(currentPos);
        String why = refusal != null
                ? refusal.cause()
                : asideTarget != null ? "stepping aside" : takeBack != null ? "taking my block back" : "building";
        int tries = currentPos == null ? 0 : attempts.getOrDefault(currentPos, 0);
        return "build " + phase + ": " + why + " at " + cell + ", stance "
                + (stance == null ? "-" : coords(stance)) + ", attempts " + tries + ", recoveries "
                + selfRecoveries + ", deferred " + deferred.size() + ", progress " + passProgress;
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

    private static Pos toPos(BlockPos pos) {
        return new Pos(pos.getX(), pos.getY(), pos.getZ());
    }

    private static PlacementRefusal protectedRefusal(Pos pos, Place place) {
        return new PlacementRefusal.Protected(
                pos,
                place.name(),
                place.kind().name().toLowerCase(Locale.ROOT),
                place.radius(),
                place.anchor().x(),
                place.anchor().z(),
                0);
    }
}
