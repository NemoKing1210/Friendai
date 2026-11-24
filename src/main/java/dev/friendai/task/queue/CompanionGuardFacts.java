package dev.friendai.task.queue;

import dev.friendai.agent.goal.Goal;
import dev.friendai.agent.goal.GoalBoard;
import dev.friendai.agent.goal.GoalStatus;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.queue.BlockSurvey;
import dev.friendai.execution.queue.GuardFacts;
import dev.friendai.execution.queue.PlanArchive;
import dev.friendai.execution.queue.PlanStatus;
import dev.friendai.server.runtime.ServerRuntime;
import dev.friendai.server.runtime.ServerRuntimeRegistry;
import dev.friendai.task.inventory.ItemMatcher;
import dev.friendai.world.block.BlockMatcher;
import dev.friendai.world.block.BlockSearch;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/** One server-thread facts pass for queue guards. */
public final class CompanionGuardFacts implements GuardFacts {
    private final CompanionEntity companion;
    private final Map<String, Integer> itemCounts = new HashMap<>();
    private final Map<String, Integer> ownerItemCounts = new HashMap<>();
    private final Map<String, Integer> ownerOwnedCounts = new HashMap<>();
    private final Map<String, Integer> handedOverCounts = new HashMap<>();
    private final Map<String, Integer> pickedUpCounts = new HashMap<>();
    private final Map<String, Survey> blockSurveys = new HashMap<>();
    private Integer emptySlots, ownerDistance;
    private Float healthFraction;
    private String dimension;
    private double totalDistance;
    private double lastX = Double.NaN, lastZ = Double.NaN;
    private String lastDimension = "";
    /** Faster than any legitimate walk in one tick; a bigger hop is a teleport, not distance covered. */
    private static final double MAX_STEP_PER_TICK = 16.0;

    private long planStartTick;
    private long planStartDistance;
    private long elapsedTicks;
    private int travelledBlocks;
    private String surveyScope = "";
    private int surveyBudget = BLOCK_BUDGET_CELLS;
    private long surveyBudgetTick = Long.MIN_VALUE;
    /** How many cells one facts pass may inspect before it must answer "cannot tell yet". */
    private static final int BLOCK_BUDGET_CELLS = 40_000;
    /** How long a completed negative answer is trusted, scoped to the area it was measured in. */
    private static final long BLOCK_TTL_TICKS = 10;

    private static final int MAX_BLOCK_SURVEYS = 8;

    public CompanionGuardFacts(CompanionEntity companion) {
        this.companion = companion;
    }
    /** Starts a new pass: cheap facts are re-read, the expensive block scans are reused for a short while. */
    public void refresh() {
        refresh(0, 0);
    }
    /** Refreshes a pass and exposes distance/time relative to one stored plan baseline. */
    public void refresh(long startTick, long startDistance) {
        itemCounts.clear();
        ownerItemCounts.clear();
        ownerOwnedCounts.clear();
        handedOverCounts.clear();
        pickedUpCounts.clear();
        emptySlots = null;
        ownerDistance = null;
        healthFraction = null;
        dimension = null;
        planStartTick = startTick;
        planStartDistance = startDistance;
        double x = companion.getX(), z = companion.getZ();
        String where = dimension();
        // A portal, a teleport or a load into another world is not travel on foot: the old odometer measured another
        // place, so the jump adds nothing. The reading itself is kept, so every plan's saved baseline still measures
        // the ground covered since it started. Sampling every pass is safe: a second pass at the same position adds
        // nothing.
        boolean jumped = !Double.isNaN(lastX)
                && (!where.equals(lastDimension) || Math.hypot(x - lastX, z - lastZ) > MAX_STEP_PER_TICK);
        if (!Double.isNaN(lastX) && !jumped) totalDistance += Math.hypot(x - lastX, z - lastZ);
        lastX = x;
        lastZ = z;
        lastDimension = where;
        // Both baselines live on the queue clock and the queue odometer, never on the world age or a shared meter.
        long now = simulationTick();
        elapsedTicks = planStartTick < 0 || now < 0 ? 0 : Math.max(0, now - planStartTick);
        travelledBlocks = planStartDistance < 0
                ? 0
                : (int) Math.clamp((long) Math.floor(totalDistance - planStartDistance / 1000.0), 0, Integer.MAX_VALUE);
        refreshSurveys();
    }

    /**
     * Starts a facts pass for block surveys: the budget is per pass, and evidence from another dimension or another
     * origin describes a different square, so it is dropped rather than carried along.
     */
    private void refreshSurveys() {
        String scope = dimension() + "@" + companion.blockPosition().asLong();
        if (!scope.equals(surveyScope)) {
            blockSurveys.clear();
            surveyScope = scope;
        }
        long now = companion.level().getGameTime();
        if (now != surveyBudgetTick) {
            surveyBudget = BLOCK_BUDGET_CELLS;
            surveyBudgetTick = now;
        }
    }
    /** Continues the odometer from a saved reading, so a plan's saved baseline still measures after a reload. */
    public void restoreTravelled(long milliBlocks) {
        if (milliBlocks > 0) totalDistance = milliBlocks / 1000.0;
    }

    /** Cumulative horizontal distance in milli-blocks, retaining a plan's fractional baseline. */
    public long totalTravelledDistance() {
        return Math.round(totalDistance * 1000.0);
    }

    @Override
    public int distanceTravelled() {
        return travelledBlocks;
    }

    @Override
    public long simulationTick() {
        // The queue clock, the same one a plan's cursor baseline is stored in.
        return companion.executionCoordinator().queue().clock();
    }

    @Override
    public long travelledMilliBlocks() {
        return Math.round(totalDistance * 1000.0);
    }

    @Override
    public long timeElapsed() {
        return elapsedTicks;
    }

    @Override
    public int countItems(String id) {
        if (itemCounts.containsKey(id)) return itemCounts.get(id);
        var matcher = ItemMatcher.parse(id).orElse(null);
        if (matcher == null) return 0;
        int count = matcher.test(companion.getMainHandItem())
                ? companion.getMainHandItem().getCount()
                : 0;
        for (int i = 0; i < companion.inventory().getContainerSize(); i++) {
            ItemStack stack = companion.inventory().getItem(i);
            if (matcher.test(stack)) count += stack.getCount();
        }
        itemCounts.put(id, count);
        return count;
    }

    @Override
    public int ownerCountItems(String id) {
        if (ownerItemCounts.containsKey(id)) return ownerItemCounts.get(id);
        ServerPlayer owner = companion.owner().orElse(null);
        var matcher = owner == null ? null : ItemMatcher.parse(id).orElse(null);
        if (matcher == null) return 0;
        int count = ownerOwnedAll(matcher);
        // A drop the companion handed over at the owner's feet is evidence too, but only while it is still there:
        // the owner's pickup is confirmation, and a despawned or destroyed stack proves nothing.
        count = Math.max(count, delivered(matcher));
        ownerItemCounts.put(id, count);
        return count;
    }

    @Override
    public int ownerOwnedItems(String id) {
        if (ownerOwnedCounts.containsKey(id)) return ownerOwnedCounts.get(id);
        var owner = companion.owner().orElse(null);
        var matcher = owner == null ? null : ItemMatcher.parse(id).orElse(null);
        int count = matcher == null ? 0 : ownerOwnedAll(matcher);
        ownerOwnedCounts.put(id, count);
        return count;
    }

    private int ownerOwnedAll(ItemMatcher matcher) {
        ServerPlayer owner = companion.owner().orElse(null);
        if (owner == null) return 0;
        var inventory = owner.getInventory();
        int count = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (matcher.test(stack)) count += stack.getCount();
        }
        return count;
    }

    @Override
    public int handedOverItems(String id, long afterTick) {
        String key = id + ":" + afterTick;
        if (handedOverCounts.containsKey(key)) return handedOverCounts.get(key);
        int count = companion
                .delivered()
                .handedOver(id, afterTick, companion.level().getGameTime());
        handedOverCounts.put(key, count);
        return count;
    }

    @Override
    public int pickedUpItems(String id, long afterTick) {
        String key = id + ":" + afterTick;
        if (pickedUpCounts.containsKey(key)) return pickedUpCounts.get(key);
        int owned = ownerOwnedItems(id);
        int count =
                companion.delivered().pickedUp(id, afterTick, companion.level().getGameTime(), owned);
        pickedUpCounts.put(key, count);
        return count;
    }

    /** What the companion handed over recently and whose drop is still in the world. */
    private int delivered(ItemMatcher matcher) {
        if (companion.delivered().isEmpty()) {
            return 0;
        }
        return companion
                .delivered()
                .count(
                        matcher.label(),
                        companion.level().getGameTime(),
                        uuid -> companion.level().getEntity(uuid) instanceof ItemEntity item
                                && !item.isRemoved()
                                && matcher.test(item.getItem()));
    }

    @Override
    public int emptySlots() {
        if (emptySlots == null) {
            int n = 0;
            for (int i = 0; i < companion.inventory().getContainerSize(); i++)
                if (companion.inventory().getItem(i).isEmpty()) n++;
            emptySlots = n;
        }
        return emptySlots;
    }

    @Override
    public float healthFraction() {
        if (healthFraction == null) healthFraction = companion.getHealth() / Math.max(1f, companion.getMaxHealth());
        return healthFraction;
    }

    @Override
    public int foodLevel() {
        return companion.syncedFood();
    }

    @Override
    public int ownerDistance() {
        if (ownerDistance == null) {
            ServerPlayer owner = companion.owner().orElse(null);
            ownerDistance = owner == null || owner.level() != companion.level()
                    ? -1
                    : (int) Math.round(Math.sqrt(companion.distanceToSqr(owner)));
        }
        return ownerDistance;
    }

    @Override
    public int countBlocks(String id, int radius) {
        var matcher = BlockMatcher.parse(id).orElse(null);
        if (matcher == null) return 0;
        int n = 0;
        BlockPos p = companion.blockPosition();
        for (int x = -radius; x <= radius; x++)
            for (int y = -radius; y <= radius; y++)
                for (int z = -radius; z <= radius; z++) {
                    BlockPos at = p.offset(x, y, z);
                    if (companion.level().hasChunkAt(at)
                            && matcher.test(companion.level().getBlockState(at))) n++;
                }
        return n;
    }

    @Override
    public String dimension() {
        if (dimension == null)
            dimension = companion.level().dimension().identifier().toString();
        return dimension;
    }

    @Override
    public boolean blockInFront(String id, int distance) {
        var matcher = BlockMatcher.parse(id).orElse(null);
        if (matcher == null || distance < 1 || distance > 8) return false;
        BlockPos feet = companion.blockPosition().relative(companion.getDirection(), distance);
        if (!companion.level().hasChunkAt(feet)) return false;
        return matcher.test(companion.level().getBlockState(feet))
                || (companion.level().hasChunkAt(feet.above())
                        && matcher.test(companion.level().getBlockState(feet.above())));
    }

    @Override
    public boolean blockAt(String id, int x, int y, int z) {
        return Boolean.TRUE.equals(blockAtOrNull(id, x, y, z));
    }

    @Override
    public Boolean blockAtOrNull(String id, int x, int y, int z) {
        var matcher = BlockMatcher.parse(id).orElse(null);
        if (matcher == null) return null;
        BlockPos pos = new BlockPos(x, y, z);
        // An unloaded chunk is missing evidence, not an absent block.
        if (!companion.level().hasChunkAt(pos)) return null;
        return matcher.test(companion.level().getBlockState(pos));
    }

    @Override
    public String biome() {
        BlockPos feet = companion.blockPosition();
        if (!companion.level().hasChunkAt(feet)) return "";
        return companion
                .level()
                .getBiome(feet)
                .unwrapKey()
                .map(key -> key.identifier().toString())
                .orElse("");
    }

    @Override
    public boolean fluidAhead() {
        BlockPos feet = companion.blockPosition().relative(companion.getDirection());
        if (!companion.level().hasChunkAt(feet)) return false;
        return !companion.level().getBlockState(feet).getFluidState().isEmpty()
                || (companion.level().hasChunkAt(feet.above())
                        && !companion
                                .level()
                                .getBlockState(feet.above())
                                .getFluidState()
                                .isEmpty());
    }

    @Override
    public Boolean ownerOnline() {
        return companion.owner().isPresent();
    }

    @Override
    public Boolean daytime() {
        // The vanilla 0..24000 clock; day starts at sunrise and night at sunset.
        return Math.floorMod(companion.level().getDefaultClockTime(), 24_000L) < 12_000L;
    }

    @Override
    public String weather() {
        return companion.level().isThundering() ? "thunder" : companion.level().isRaining() ? "rain" : "clear";
    }

    @Override
    public int xpLevel() {
        return companion.syncedXpLevel();
    }

    @Override
    public Boolean stationWithin(String station, int radius) {
        BlockMatcher matcher = BlockMatcher.parse(station).orElse(null);
        if (matcher == null || !(companion.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return null;
        }
        return BlockSearch.nearest(level, companion.blockPosition(), radius, matcher, Set.of())
                .isPresent();
    }

    @Override
    public Boolean canHarvest(String block) {
        Identifier id = Identifier.tryParse(block);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            return null;
        }
        var state = BuiltInRegistries.BLOCK.getValue(id).defaultBlockState();
        if (companion.getMainHandItem().isCorrectToolForDrops(state)) {
            return true;
        }
        for (int i = 0; i < companion.inventory().getContainerSize(); i++) {
            ItemStack stack = companion.inventory().getItem(i);
            if (!stack.isEmpty() && stack.isCorrectToolForDrops(state)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Boolean planDone(long planId) {
        var queue = companion.executionCoordinator().queue();
        var live = queue.plan(planId);
        if (live.isPresent()) {
            return queue.planStatus(planId) == PlanStatus.DONE;
        }
        // A finished plan leaves the queue but is archived: the archive is the record of how it ended.
        return queue.archives().stream()
                .filter(archive -> archive.id() == planId)
                .findFirst()
                .map(PlanArchive::success)
                .orElse(null);
    }

    @Override
    public Boolean goalMet(long goalId) {
        GoalBoard board = board();
        if (board == null) {
            return null;
        }
        Optional<Goal> live = board.find(goalId);
        if (live.isPresent()) {
            return live.get().status() == GoalStatus.DONE;
        }
        // A finished goal leaves the active list; the kept record still answers the question either way.
        return board.finished().stream()
                .filter(goal -> goal.id() == goalId)
                .findFirst()
                .map(goal -> goal.status() == GoalStatus.DONE)
                .orElse(null);
    }

    private GoalBoard board() {
        if (companion.ownerUuid() == null) {
            return null;
        }
        ServerRuntime runtime =
                ServerRuntimeRegistry.find(companion.level().getServer()).orElse(null);
        return runtime == null ? null : runtime.goals().board(companion.ownerUuid());
    }

    @Override
    public boolean hasBlocks(String id, int radius, int count) {
        return Boolean.TRUE.equals(hasBlocksOrNull(id, radius, count));
    }

    @Override
    public Boolean hasBlocksOrNull(String id, int radius, int count) {
        var matcher = BlockMatcher.parse(id).orElse(null);
        if (matcher == null || radius < 1 || radius > 32 || count < 1 || count > 256) return null;
        String key = id + ":" + radius + ":" + count;
        Survey survey = blockSurveys.get(key);
        if (survey == null) {
            if (blockSurveys.size() >= MAX_BLOCK_SURVEYS) blockSurveys.clear();
            BlockPos origin = companion.blockPosition().immutable();
            survey = new Survey(new BlockSurvey(origin.getX(), origin.getY(), origin.getZ(), radius, count), matcher);
            blockSurveys.put(key, survey);
        }
        if (survey.shape.complete()) {
            if (survey.shape.stillMatches(survey.cells)) return true;
            // Positive evidence that no longer holds describes the past, not the world in front of the companion.
            survey.shape.reset();
        }
        long now = companion.level().getGameTime();
        if (survey.decidedTick >= 0 && now >= survey.decidedTick && now - survey.decidedTick < BLOCK_TTL_TICKS) {
            return false;
        }
        BlockSurvey.Result result = survey.shape.advance(survey.cells, Math.max(0, surveyBudget));
        surveyBudget = Math.max(0, surveyBudget - result.inspected());
        return switch (result.outcome()) {
            case MATCHED -> true;
            case ABSENT -> {
                survey.decidedTick = now;
                yield false;
            }
            case INCOMPLETE -> null;
        };
    }

    /** One scoped survey and the world it reads: the survey itself stays pure and the cells come from the level. */
    private final class Survey {
        private final BlockSurvey shape;
        private final BlockSurvey.Cells cells;
        private long decidedTick = -1;

        private Survey(BlockSurvey shape, BlockMatcher matcher) {
            this.shape = shape;
            this.cells = new BlockSurvey.Cells() {
                @Override
                public boolean loaded(int x, int y, int z) {
                    return companion.level().hasChunkAt(new BlockPos(x, y, z));
                }

                @Override
                public boolean matches(int x, int y, int z) {
                    return matcher.test(companion.level().getBlockState(new BlockPos(x, y, z)));
                }
            };
        }
    }
}
