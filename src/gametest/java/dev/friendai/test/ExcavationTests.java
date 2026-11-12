package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.log.TaskLogEvent;
import dev.friendai.features.mining.MiningBounds;
import dev.friendai.features.mining.excavation.ExcavationDirection;
import dev.friendai.features.mining.excavation.ExcavationGeometry;
import dev.friendai.features.mining.excavation.ExcavationSpec;
import dev.friendai.features.mining.minecraft.mining.MiningSession;
import dev.friendai.features.mining.minecraft.task.ExcavateTask;
import dev.friendai.features.mining.minecraft.task.MineBlockAtTask;
import dev.friendai.foundation.diagnostics.LogEvents;
import dev.friendai.foundation.spatial.Node;
import dev.friendai.foundation.spatial.PathingConfig;
import dev.friendai.server.configuration.ServerSettings;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.server.execution.TaskEventSink;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public class ExcavationTests {
    private static final class Result implements TaskEventSink {
        TaskStatus status;
        String summary;
        final List<TaskLogEvent> diagnostics = new ArrayList<>();

        @Override
        public void onFinished(int id, String toolName, TaskStatus status, String summary) {
            this.status = status;
            this.summary = summary;
        }

        @Override
        public void onDiagnostic(TaskLogEvent event) {
            diagnostics.add(event);
        }

        @Override
        public void onCancelled(int id, String toolName, String reason) {}
    }

    private static ExecutionCoordinator runner(CompanionEntity c, Result result) {
        ExecutionCoordinator r = new ExecutionCoordinator(c, () -> 300, () -> false);
        r.setEventSink(result);
        return r;
    }

    private static CompanionEntity companion(GameTestHelper h) {
        GameTestSupport.platform(h, 24);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setOwner(java.util.UUID.randomUUID());
        c.inventory().addItem(new ItemStack(Items.DIAMOND_PICKAXE));
        return c;
    }

    private static void observe(GameTestHelper h, CompanionEntity c, BlockPos... positions) {
        for (BlockPos pos : positions) {
            h.assertTrue(GameTestSupport.observeVisible(h, c, h.absolutePos(pos)), "cell should be visible at " + pos);
        }
    }

    private static int count(GameTestHelper h, Block block, BlockPos... positions) {
        int found = 0;
        for (BlockPos pos : positions) {
            if (h.getLevel().getBlockState(h.absolutePos(pos)).is(block)) {
                found++;
            }
        }
        return found;
    }

    private static ExcavationSpec spec(ExcavationGeometry geometry, int count) {
        return ExcavationSpec.of(geometry, count, null, 0, true, false, false, true, 0)
                .orElseThrow();
    }

    /**
     * A box whose corners are world coordinates, because the task reads the level instead of the structure frame. A
     * geometry built from structure-relative corners names an unloaded chunk to the session's cell feed.
     */
    private static ExcavationGeometry box(GameTestHelper h, BlockPos first, BlockPos last) {
        BlockPos a = h.absolutePos(first);
        BlockPos b = h.absolutePos(last);
        return ExcavationGeometry.box(a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ())
                .orElseThrow();
    }

    /** Ticks the real session through the ordinary diagnostic boundary without changing its retry policy. */
    private static final class MiningProbe implements Task {
        final MiningSession session;
        final List<String> retryStates = new ArrayList<>();

        MiningProbe(CompanionEntity companion, BlockPos target) {
            BlockPos at = companion.blockPosition();
            session = new MiningSession(
                    companion, new MiningBounds(new Node(at.getX(), at.getY(), at.getZ()), 8), 1, 200);
            session.setTargets(List.of(target), state -> state.is(Blocks.STONE));
        }

        @Override
        public String type() {
            return "mine_block_at";
        }

        @Override
        public void start(TaskContext context) {}

        @Override
        public TaskStatus tick(TaskContext context) {
            MiningSession.State state = session.tick(context);
            String detail = session.debugReason();
            if (detail.contains(" x" + (retryStates.size() + 1) + " cand=")) {
                retryStates.add(detail);
            }
            return switch (state) {
                case WORKING -> TaskStatus.RUNNING;
                case FAILED -> TaskStatus.FAILED;
                case EXTRACTED, EXHAUSTED -> TaskStatus.SUCCESS;
            };
        }

        @Override
        public void stop(TaskContext context) {
            session.stop(context);
        }

        @Override
        public String describe() {
            return "mining diagnostic probe";
        }

        @Override
        public String resultSummary() {
            return session.failure() == null ? "mined" : session.failure().key();
        }
    }

    @GameTest(maxTicks = 300)
    public void miningRetriesReportOwnedRefusalFacts(GameTestHelper h) {
        CompanionEntity c = companion(h);
        c.inventory().clearContent();
        BlockPos cell = new BlockPos(4, 2, 2);
        h.setBlock(cell, Blocks.STONE);
        BlockPos target = h.absolutePos(cell);
        MiningProbe task = new MiningProbe(c, target);
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(task, "mine_block_at");

        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "the refusal has not reached the retry cap");
            List<TaskLogEvent> retries = result.diagnostics.stream()
                    .filter(event -> event.event().equals(LogEvents.RESOURCE_DECISION)
                            && "diagnostic".equals(event.facts().get("reason")))
                    .toList();
            h.assertTrue(retries.size() == 3, "each of the three retries must report once: " + retries);
            h.assertTrue(task.retryStates.size() == 3, "the session retry cap changed: " + task.retryStates);
            for (int index = 0; index < retries.size(); index++) {
                TaskLogEvent event = retries.get(index);
                String code = index == 0 ? "refused-no_tool" : "no-path";
                h.assertTrue(code.equals(event.facts().get("diagnostic")), "wrong retry code: " + event);
                h.assertTrue(
                        (target.getX() + " " + target.getY() + " " + target.getZ())
                                .equals(event.facts().get("target")),
                        "retry lost its selected target: " + event);
                h.assertTrue("1".equals(event.facts().get("excludedTargets")), "wrong excluded target count: " + event);
                h.assertTrue("0".equals(event.facts().get("excludedStances")), "wrong excluded stance count: " + event);
                h.assertTrue("1".equals(event.facts().get("candidates")), "wrong remaining candidate count: " + event);
                h.assertTrue(
                        task.retryStates.get(index).contains("x" + (index + 1) + " cand=1 ex=1/0"),
                        "facts differ from session-owned collections: " + task.retryStates);
            }
            h.assertTrue(
                    "friendai.mining.no_tool".equals(task.session.failure().key()),
                    "later unreachable retries replaced the typed refusal: " + result.summary);
            h.assertTrue(h.getBlockState(cell).is(Blocks.STONE), "a refused target was broken");
        });
    }

    @GameTest(maxTicks = 300)
    public void successfulMiningHasNoRetryDiagnostic(GameTestHelper h) {
        CompanionEntity c = companion(h);
        BlockPos cell = new BlockPos(4, 2, 2);
        h.setBlock(cell, Blocks.STONE);
        MiningProbe task = new MiningProbe(c, h.absolutePos(cell));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(task, "mine_block_at");

        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "the successful mine is still running");
            h.assertTrue(task.session.debugReason().isEmpty(), "successful mining retained a diagnostic");
            h.assertTrue(
                    result.diagnostics.stream()
                            .noneMatch(event -> event.event().equals(LogEvents.RESOURCE_DECISION)
                                    && "diagnostic".equals(event.facts().get("reason"))),
                    "an empty mining diagnostic emitted a decision");
            h.assertTrue(h.getBlockState(cell).isAir(), "successful mining did not break the target");
        });
    }

    @GameTest(maxTicks = 1600)
    public void boxMinesEveryCellAndCollectsDrops(GameTestHelper h) {
        CompanionEntity c = companion(h);
        BlockPos[] cells = {new BlockPos(4, 2, 2), new BlockPos(5, 2, 2), new BlockPos(4, 2, 3), new BlockPos(5, 2, 3)};
        for (BlockPos cell : cells) {
            h.setBlock(cell, Blocks.STONE);
        }
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new ExcavateTask(spec(box(h, cells[0], cells[3]), 4)), "excavate");

        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(
                    result.status != null && result.status != TaskStatus.RUNNING,
                    "the box job is still running: " + result.summary);
            h.assertTrue(result.status == TaskStatus.SUCCESS, "box failed: " + result.summary);
            h.assertTrue(count(h, Blocks.STONE, cells) == 0, "box cells remain: " + result.summary);
            h.assertTrue(
                    c.inventory().countItem(Items.COBBLESTONE) >= 4,
                    "cobblestone collected: " + c.inventory().countItem(Items.COBBLESTONE));
        });
    }

    /**
     * "Dig a 3x3 pit, 2 deep": the model asked for a shaft down from where the companion stands, and the first layer of
     * the shaft was the air at its feet, so only one layer of earth came out. A shaft started by "near" now begins with
     * the ground under the feet.
     */
    @GameTest(maxTicks = 2400)
    public void aPitDugFromWhereTheCompanionStandsGoesTheFullDepth(GameTestHelper h) {
        CompanionEntity c = companion(h);
        for (int x = 0; x <= 7; x++) {
            for (int z = 0; z <= 7; z++) {
                h.setBlock(x, 1, z, Blocks.COARSE_DIRT);
                h.setBlock(x, 0, z, Blocks.COARSE_DIRT);
            }
        }
        var tools = GameTestToolRegistry.create(c, () -> 32).bind(c);
        String json = "{\"shape\":\"shaft\",\"run\":{\"direction\":\"down\",\"length\":2,\"width\":3,\"height\":3},"
                + "\"start\":{\"near\":\"self\"}}";
        var started = tools.execute(new dev.friendai.agent.llm.model.ToolCall(
                "excavation-test",
                "excavate",
                com.google.gson.JsonParser.parseString(json).getAsJsonObject(),
                json,
                null));
        h.assertTrue(!started.error(), "excavate starts: " + started.content());
        h.succeedWhen(() -> {
            c.executionCoordinator().tick();
            int left = 0;
            for (int x = 2; x <= 4; x++) {
                for (int z = 2; z <= 4; z++) {
                    for (int y = 0; y <= 1; y++) {
                        if (h.getBlockState(new BlockPos(x, y, z)).is(Blocks.COARSE_DIRT)) left++;
                    }
                }
            }
            h.assertValueEqual(left, 0, "earth left in the pit's two layers");
        });
    }

    @GameTest(maxTicks = 1600)
    public void tunnelLeavesAWalkableCorridor(GameTestHelper h) {
        CompanionEntity c = companion(h);
        BlockPos[] cells = {
            new BlockPos(4, 2, 2), new BlockPos(4, 3, 2), new BlockPos(5, 2, 2),
            new BlockPos(5, 3, 2), new BlockPos(6, 2, 2), new BlockPos(6, 3, 2)
        };
        for (BlockPos cell : cells) {
            h.setBlock(cell, Blocks.STONE);
        }
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        BlockPos origin = h.absolutePos(new BlockPos(4, 2, 2));
        runner.start(
                new ExcavateTask(spec(
                        ExcavationGeometry.tunnel(
                                        ExcavationDirection.EAST, origin.getX(), origin.getY(), origin.getZ(), 3, 1, 2)
                                .orElseThrow(),
                        6)),
                "excavate");

        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(
                    result.status != null && result.status != TaskStatus.RUNNING,
                    "the tunnel job is still running: " + result.summary);
            h.assertTrue(result.status == TaskStatus.SUCCESS, "tunnel failed: " + result.summary);
            h.assertTrue(count(h, Blocks.STONE, cells) == 0, "tunnel cells remain: " + result.summary);
        });
    }

    /**
     * A descending stair is a passage, not a row of holes: every cross-section of the stair carries the body and one
     * cell of headroom above it, and the companion digs it from inside the stair, so no cell beside the corridor is
     * ever opened. This is the shape an owner reported as a low staircase the companion could not walk down and had to
     * mine from the side.
     */
    @GameTest(maxTicks = 4000)
    public void stairLeavesAWalkablePassage(GameTestHelper h) {
        int base = 240;
        int height = ExcavationGeometry.MIN_STAIR_HEIGHT;
        GameTestSupport.platformAt(h, base, 24);
        for (int x = 3; x <= 11; x++) {
            for (int z = 3; z <= 7; z++) {
                for (int y = base - 8; y <= base + 4; y++) {
                    h.setBlock(x, y, z, Blocks.STONE);
                }
            }
        }
        for (int dy = 0; dy < height; dy++) {
            h.setBlock(4, base + 1 + dy, 5, Blocks.AIR);
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 4, base + 1, 5);
        c.setOwner(java.util.UUID.randomUUID());
        c.inventory().addItem(new ItemStack(Items.DIAMOND_PICKAXE));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        int length = 6;
        BlockPos origin = h.absolutePos(new BlockPos(4, base + 1, 5));
        ExcavateTask task = new ExcavateTask(spec(
                ExcavationGeometry.stair(
                                ExcavationDirection.EAST, origin.getX(), origin.getY(), origin.getZ(), length, 1, 2)
                        .orElseThrow(),
                length * height));
        runner.start(task, "excavate");

        TaskTrace.of(h, runner, c).succeedWhen(() -> {
            runner.tick();
            h.assertTrue(
                    result.status != null && result.status != TaskStatus.RUNNING,
                    "the stair job is still running: " + task.debugStatus());
            h.assertTrue(result.status == TaskStatus.SUCCESS, "stair failed: " + result.summary);
            StringBuilder holes = new StringBuilder();
            for (int step = 0; step < length; step++) {
                for (int dy = 0; dy < height; dy++) {
                    BlockPos cell = new BlockPos(4 + step, base + 1 - step + dy, 5);
                    if (!h.getLevel().getBlockState(h.absolutePos(cell)).isAir()) {
                        holes.append(step).append(':').append(cell).append("; ");
                    }
                }
            }
            h.assertTrue(holes.isEmpty(), "the stair still has unbroken cells: " + holes + "; " + task.debugStatus());
            StringBuilder outside = new StringBuilder();
            for (int x = 4; x < 4 + length; x++) {
                for (int y = base - 6; y <= base + 3; y++) {
                    for (int z = 4; z <= 6; z++) {
                        int step = x - 4;
                        boolean stair = z == 5 && y >= base + 1 - step && y < base + 1 - step + height;
                        if (!stair && !h.getBlockState(new BlockPos(x, y, z)).is(Blocks.STONE)) {
                            outside.append(x)
                                    .append(' ')
                                    .append(y)
                                    .append(' ')
                                    .append(z)
                                    .append("; ");
                        }
                    }
                }
            }
            h.assertTrue(
                    outside.isEmpty(),
                    "the stair opened cells beside its own cross-section: " + outside + "; " + task.debugStatus());
        });
    }

    @GameTest(maxTicks = 1200)
    public void containersAreSkippedByDefault(GameTestHelper h) {
        CompanionEntity c = companion(h);
        BlockPos stone = new BlockPos(4, 2, 2);
        BlockPos chest = new BlockPos(5, 2, 2);
        h.setBlock(stone, Blocks.STONE);
        h.setBlock(chest, Blocks.CHEST);
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new ExcavateTask(spec(box(h, stone, chest), 2)), "excavate");

        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(
                    result.status != null && result.status != TaskStatus.RUNNING,
                    "the container job is still running: " + result.summary);
            h.assertTrue(result.status == TaskStatus.SUCCESS, "container job failed: " + result.summary);
            h.assertTrue(count(h, Blocks.CHEST, chest) == 1, "chest was broken: " + result.summary);
            h.assertTrue(count(h, Blocks.STONE, stone) == 0, "stone was not mined: " + result.summary);
            h.assertTrue(result.summary.contains("skipped"), "summary should name the skip: " + result.summary);
        });
    }

    @GameTest(maxTicks = 1600)
    public void shaftRefusesTheCellAboveLava(GameTestHelper h) {
        CompanionEntity c = companion(h);
        h.setBlock(new BlockPos(4, 1, 2), Blocks.LAVA);
        BlockPos low = new BlockPos(4, 2, 2);
        BlockPos middle = new BlockPos(4, 3, 2);
        BlockPos high = new BlockPos(4, 4, 2);
        h.setBlock(low, Blocks.STONE);
        h.setBlock(middle, Blocks.STONE);
        h.setBlock(high, Blocks.STONE);
        observe(h, c, low, middle, high);
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        BlockPos origin = h.absolutePos(new BlockPos(4, 4, 2));
        runner.start(
                new ExcavateTask(spec(
                        ExcavationGeometry.shaft(
                                        ExcavationDirection.DOWN, origin.getX(), origin.getY(), origin.getZ(), 3, 1, 1)
                                .orElseThrow(),
                        3)),
                "excavate");

        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(
                    result.status != null && result.status != TaskStatus.RUNNING,
                    "the shaft job is still running: " + result.summary);
            h.assertTrue(count(h, Blocks.STONE, low) == 1, "the cell above lava was broken: " + result.summary);
            h.assertTrue(count(h, Blocks.STONE, high) == 0, "the safe top cell was not mined: " + result.summary);
            h.assertTrue(!c.isInLava(), "the companion ended in lava");
        });
    }

    @GameTest(maxTicks = 1200)
    public void veinFalseMinesOnlyThePointedBlock(GameTestHelper h) {
        CompanionEntity c = companion(h);
        BlockPos pointed = new BlockPos(4, 2, 2);
        BlockPos neighbour = new BlockPos(4, 2, 3);
        h.setBlock(pointed, Blocks.IRON_ORE);
        h.setBlock(neighbour, Blocks.IRON_ORE);
        observe(h, c, pointed, neighbour);
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new MineBlockAtTask(h.absolutePos(pointed), false, 32, true), "mine_block_at");

        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(
                    result.status != null && result.status != TaskStatus.RUNNING,
                    "the mine is still running: " + result.summary);
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(count(h, Blocks.IRON_ORE, pointed) == 0, "pointed ore was not mined: " + result.summary);
            h.assertTrue(count(h, Blocks.IRON_ORE, neighbour) == 1, "vein:false mined a neighbour: " + result.summary);
        });
    }

    @GameTest(maxTicks = 1600)
    public void veinNeighbourAboveLavaIsRefused(GameTestHelper h) {
        CompanionEntity c = companion(h);
        BlockPos pointed = new BlockPos(4, 2, 2);
        BlockPos neighbour = new BlockPos(4, 2, 3);
        h.setBlock(pointed, Blocks.IRON_ORE);
        h.setBlock(neighbour, Blocks.IRON_ORE);
        h.setBlock(new BlockPos(4, 1, 3), Blocks.LAVA);
        observe(h, c, pointed, neighbour);
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(new MineBlockAtTask(h.absolutePos(pointed), true, 32, true), "mine_block_at");

        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(
                    result.status != null && result.status != TaskStatus.RUNNING,
                    "the vein mine is still running: " + result.summary);
            h.assertTrue(count(h, Blocks.IRON_ORE, pointed) == 0, "pointed ore was not mined: " + result.summary);
            h.assertTrue(
                    count(h, Blocks.IRON_ORE, neighbour) == 1,
                    "the neighbour above lava was broken: " + result.summary);
            h.assertTrue(!c.isInLava(), "the companion ended in lava");
        });
    }

    /**
     * A path search whose budget is zero never expands a node, so the session stays in its planning phase and the
     * 200-tick stall fallback fires deterministically instead of racing a real search.
     */
    private static PathingConfig stalledPathing() {
        return new PathingConfig(true, true, PathingConfig.DEFAULTS.breakBlacklist(), List.of(), 0, 20_000, 64);
    }

    /** A runner whose own task context carries the stalled pathing config; the task cannot walk to its region. */
    private static ExecutionCoordinator stalledRunner(CompanionEntity c, Result result) {
        ServerSettings settings = new ServerSettings(null, stalledPathing(), null, null, null, null, 16);
        ExecutionCoordinator r = new ExecutionCoordinator(c, () -> 300, () -> false, () -> true, () -> settings);
        r.setEventSink(result);
        return r;
    }

    /**
     * A region away from the support cell, so the fallback descent cannot count as region progress. The geometry
     * carries world coordinates because the task reads the level directly: a spec built from test-relative corners
     * points at an unloaded chunk, so the session would give up on the missing chunk instead of maturing the stall.
     */
    private static ExcavateTask stalledExcavate(GameTestHelper h) {
        return stalledExcavate(h, true);
    }

    private static ExcavateTask stalledExcavate(GameTestHelper h, boolean collect) {
        BlockPos[] cells = {new BlockPos(4, 2, 2), new BlockPos(5, 2, 2), new BlockPos(4, 2, 3), new BlockPos(5, 2, 3)};
        for (BlockPos cell : cells) {
            h.setBlock(cell, Blocks.STONE);
        }
        BlockPos first = h.absolutePos(cells[0]);
        BlockPos last = h.absolutePos(cells[3]);
        ExcavationSpec spec = ExcavationSpec.of(
                        ExcavationGeometry.box(
                                        first.getX(), first.getY(), first.getZ(), last.getX(), last.getY(), last.getZ())
                                .orElseThrow(),
                        cells.length,
                        null,
                        0,
                        collect,
                        false,
                        false,
                        true,
                        0)
                .orElseThrow();
        return new ExcavateTask(spec);
    }

    @GameTest(maxTicks = 400)
    public void excavateFallbackDigsBelowAPlanningStall(GameTestHelper h) {
        CompanionEntity c = companion(h);
        h.setBlock(new BlockPos(2, 0, 2), Blocks.STONE);
        Result result = new Result();
        ExecutionCoordinator runner = stalledRunner(c, result);
        ExcavateTask task = stalledExcavate(h);
        BlockPos support = h.absolutePos(new BlockPos(2, 1, 2));
        runner.start(task, "excavate");

        TaskTrace.of(h, runner, c).succeedWhen(() -> {
            runner.tick();
            h.assertTrue(
                    h.getLevel().getBlockState(support).isAir(),
                    "the fallback did not dig the support: " + task.debugStatus());
            h.assertTrue(
                    task.debugStatus().contains("fallback-dig"),
                    "the fallback was not reported: " + task.debugStatus());
            h.assertTrue(
                    result.status == null,
                    "the fallback ended the task early: " + result.status + " " + result.summary);
        });
    }

    @GameTest(maxTicks = 400)
    public void excavateFallbackFailsWhenTheDescentIsRefused(GameTestHelper h) {
        CompanionEntity c = companion(h);
        h.setBlock(new BlockPos(2, 1, 2), Blocks.BEDROCK);
        h.setBlock(new BlockPos(2, 0, 2), Blocks.STONE);
        Result result = new Result();
        ExecutionCoordinator runner = stalledRunner(c, result);
        ExcavateTask task = stalledExcavate(h);
        BlockPos support = h.absolutePos(new BlockPos(2, 1, 2));
        runner.start(task, "excavate");

        TaskTrace.of(h, runner, c).succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "still running after the refusal: " + task.debugStatus());
            h.assertTrue(
                    task.debugStatus().contains("fallback-refused"),
                    "the refusal was not reported: " + task.debugStatus());
            h.assertTrue(h.getLevel().getBlockState(support).is(Blocks.BEDROCK), "the refused support changed");
        });
    }

    @GameTest(maxTicks = 400)
    public void excavateFallbackRefusesAnUnsafeLanding(GameTestHelper h) {
        CompanionEntity c = companion(h);
        h.setBlock(new BlockPos(2, 0, 2), Blocks.LAVA);
        Result result = new Result();
        ExecutionCoordinator runner = stalledRunner(c, result);
        ExcavateTask task = stalledExcavate(h);
        BlockPos support = h.absolutePos(new BlockPos(2, 1, 2));
        runner.start(task, "excavate");

        TaskTrace.of(h, runner, c).succeedWhen(() -> {
            runner.tick();
            h.assertTrue(
                    result.status == TaskStatus.FAILED,
                    "still running after the unsafe landing: " + task.debugStatus());
            h.assertTrue(
                    task.debugStatus().contains("fallback-refused"),
                    "the unsafe landing was not refused: " + task.debugStatus());
            h.assertTrue(h.getLevel().getBlockState(support).is(Blocks.STONE), "the support above lava was broken");
            h.assertTrue(!c.isInLava(), "the companion ended in lava");
        });
    }

    @GameTest(maxTicks = 400)
    public void excavateFallbackRefusesAContainerSupport(GameTestHelper h) {
        CompanionEntity c = companion(h);
        // A chest is not a full cube: its 0.875 collision top drops blockPosition() into the cell above it, so the
        // inspected support is the empty cell below and the Container branch is never reached. A shulker box is a
        // full cube, so the companion stands on it and the support read below its feet is the container itself.
        h.setBlock(new BlockPos(2, 1, 2), Blocks.SHULKER_BOX);
        h.setBlock(new BlockPos(2, 0, 2), Blocks.STONE);
        Result result = new Result();
        ExecutionCoordinator runner = stalledRunner(c, result);
        ExcavateTask task = stalledExcavate(h);
        runner.start(task, "excavate");

        TaskTrace.of(h, runner, c).succeedWhen(() -> {
            runner.tick();
            BlockPos support = c.blockPosition().below();
            h.assertTrue(
                    h.getLevel().getBlockState(support).is(Blocks.SHULKER_BOX),
                    "the companion should stand on a full-cube container support: " + support);
            h.assertTrue(
                    result.status == TaskStatus.FAILED,
                    "still running after the container support: " + task.debugStatus());
            h.assertTrue(
                    task.debugStatus().contains("fallback-refused"),
                    "the container support was not refused: " + task.debugStatus());
            h.assertTrue(
                    h.getLevel().getBlockState(support).is(Blocks.SHULKER_BOX), "the container support was broken");
        });
    }

    /**
     * The fallback is capped at three descents, so the fourth stalled window must end the task rather than bolting
     * another shaft under the region. The column under the floor stays solid for four falls: with the cap removed the
     * fourth cell would be dug, and the test would fail on the still-solid assertion instead of waiting out its budget.
     * The companion must also stay over that column, or a raised cap could pass by digging somewhere else.
     */
    @GameTest(maxTicks = 2400)
    public void excavateFallbackStopsAtTheDescentCap(GameTestHelper h) {
        CompanionEntity c = companion(h);
        for (int y = 0; y >= -3; y--) {
            h.setBlock(new BlockPos(2, y, 2), Blocks.STONE);
        }
        Result result = new Result();
        ExecutionCoordinator runner = stalledRunner(c, result);
        ExcavateTask task = stalledExcavate(h, false);
        BlockPos column = h.absolutePos(new BlockPos(2, 0, 2));
        BlockPos fourth = h.absolutePos(new BlockPos(2, -2, 2));
        runner.start(task, "excavate");

        TaskTrace.of(h, runner, c).succeedWhen(() -> {
            runner.tick();
            h.assertTrue(
                    c.blockPosition().getX() == column.getX()
                            && c.blockPosition().getZ() == column.getZ(),
                    "the companion drifted off the shaft: " + c.blockPosition());
            h.assertTrue(
                    h.getLevel().getBlockState(fourth).is(Blocks.STONE),
                    "a fourth descent dug below the cap: " + task.debugStatus());
            h.assertTrue(
                    result.status == TaskStatus.FAILED, "still running after three descents: " + task.debugStatus());
            h.assertTrue(
                    task.debugStatus().contains("fallback-cap"), "the cap was not reported: " + task.debugStatus());
            h.assertTrue(
                    task.resultSummary().contains("capped at 3"),
                    "the summary should name the cap: " + task.resultSummary());
        });
    }
}
