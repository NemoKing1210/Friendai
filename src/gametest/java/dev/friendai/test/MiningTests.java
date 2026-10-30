package dev.friendai.test;

import dev.friendai.debug.DebugTarget;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.features.mining.MiningBounds;
import dev.friendai.features.mining.minecraft.mining.BlockMatcher;
import dev.friendai.features.mining.minecraft.mining.BreakAction;
import dev.friendai.features.mining.minecraft.mining.ExcavationGuard;
import dev.friendai.features.mining.minecraft.task.MineBlockAtTask;
import dev.friendai.features.mining.minecraft.task.MineBlocksTask;
import dev.friendai.features.navigation.minecraft.nav.Sight;
import dev.friendai.features.navigation.pathing.Move;
import dev.friendai.features.navigation.pathing.Node;
import dev.friendai.features.navigation.pathing.PathStep;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.server.execution.TaskEventSink;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VineBlock;

public class MiningTests {

    private static final class Outcome implements TaskEventSink {
        TaskStatus status;
        String summary;

        @Override
        public void onFinished(int id, String toolName, TaskStatus status, String summary) {
            this.status = status;
            this.summary = summary;
        }

        @Override
        public void onCancelled(int id, String toolName, String reason) {}
    }

    @GameTest(maxTicks = 20)
    public void singleBlockMiningRefusesOccupiedSupport(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos support = h.absolutePos(new BlockPos(2, 1, 2));

        BreakAction.State state =
                new BreakAction(support).tick(new TaskContext(companion, h.getLevel()), block -> true);

        h.assertTrue(state == BreakAction.State.BLOCKED, "occupied support was not refused: " + state);
        h.assertTrue(h.getLevel().getBlockState(support).is(Blocks.STONE), "occupied support was destroyed");
        h.succeed();
    }

    @GameTest(maxTicks = 20)
    public void breakRefusalIsNotCountedAsMined(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos target = h.absolutePos(new BlockPos(4, 2, 2));
        h.setBlock(new BlockPos(4, 2, 2), Blocks.STONE);
        BreakAction action = new BreakAction(target);
        TaskContext context = new TaskContext(companion, h.getLevel());

        BreakAction.State first = action.tick(context, block -> !block.isAir());
        companion.hands().overrideProtection(pos -> pos.equals(target));
        BreakAction.State refused = action.tick(context, block -> !block.isAir());

        h.assertTrue(first == BreakAction.State.WORKING, "break did not start: " + first);
        h.assertTrue(refused == BreakAction.State.BLOCKED, "protection refusal counted as mined: " + refused);
        h.assertTrue(h.getLevel().getBlockState(target).is(Blocks.STONE), "protected target was destroyed");
        action.stop(context);
        h.succeed();
    }

    @GameTest(maxTicks = 20)
    public void lostToolStopsOreExtraction(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos target = h.absolutePos(new BlockPos(3, 2, 2));
        h.setBlock(new BlockPos(3, 2, 2), Blocks.DIAMOND_ORE);
        ExcavationGuard guard = guard(companion, h);

        h.assertFalse(
                guard.allowTarget(new TaskContext(companion, h.getLevel()), target),
                "ore extraction accepted without a tool that drops ore");
        companion.inventory().addItem(Items.IRON_PICKAXE.getDefaultInstance());
        h.assertTrue(
                guard.allowTarget(new TaskContext(companion, h.getLevel()), target),
                "suitable ore tool was not accepted");
        h.succeed();
    }

    @GameTest(maxTicks = 20)
    public void waterloggedNeighborRejectsCorridorBreak(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos target = h.absolutePos(new BlockPos(4, 2, 2));
        h.setBlock(new BlockPos(4, 2, 2), Blocks.STONE);
        h.setBlock(new BlockPos(5, 2, 2), Blocks.WATER);
        PathStep step = new PathStep(
                new Node(target.getX() - 1, target.getY(), target.getZ()),
                new Node(target.getX(), target.getY(), target.getZ()),
                Move.DIG,
                List.of(new Pos(target.getX(), target.getY(), target.getZ())),
                List.of(),
                1,
                0);

        h.assertFalse(
                guard(companion, h)
                        .allowBreak(
                                new TaskContext(companion, h.getLevel()),
                                step,
                                new Pos(target.getX(), target.getY(), target.getZ())),
                "corridor break next to water was allowed");
        h.assertTrue(h.getLevel().getBlockState(target).is(Blocks.STONE), "corridor block changed");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void vineIsNotASightObstruction(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        h.setBlock(new BlockPos(4, 3, 2), Blocks.CRIMSON_STEM);
        h.setBlock(new BlockPos(3, 3, 2), Blocks.VINE.defaultBlockState().setValue(VineBlock.EAST, true));
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos log = h.absolutePos(new BlockPos(4, 3, 2));
        h.assertTrue(
                Sight.firstBlockOnRay(h.getLevel(), companion, log) == null,
                "a vine has no collision and must not be a sight obstruction");
        h.succeed();
    }

    @GameTest(maxTicks = 1200)
    public void oreBelowFeetUsesSideStance(GameTestHelper h) {
        GameTestSupport.platform(h, 10);
        BlockPos ore = new BlockPos(4, 1, 4);
        h.setBlock(4, 0, 4, Blocks.STONE);
        h.setBlock(ore, Blocks.DIAMOND_ORE);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 4, 2, 4);
        h.assertTrue(
                GameTestSupport.observeVisible(h, companion, h.absolutePos(ore)),
                "diamond ore below the companion should be directly visible");
        companion.inventory().addItem(Items.IRON_PICKAXE.getDefaultInstance());
        Outcome outcome = new Outcome();
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 300, () -> false);
        runner.setEventSink(outcome);
        runner.start(
                new MineBlocksTask(BlockMatcher.parse("minecraft:diamond_ore").orElseThrow(), 1, 8), "mine_blocks");

        h.succeedWhen(() -> {
            runner.tick();
            if (outcome.status != TaskStatus.SUCCESS || companion.inventory().countItem(Items.DIAMOND) < 1) {
                return;
            }
            h.assertTrue(
                    outcome.status == TaskStatus.SUCCESS,
                    "mining did not finish: " + outcome.status + " " + outcome.summary);
            h.assertTrue(h.getLevel().getBlockState(h.absolutePos(ore)).isAir(), "ore support was not extracted");
            h.assertTrue(companion.inventory().countItem(Items.DIAMOND) >= 1, "ore drop was not collected");
            h.assertFalse(
                    companion.blockPosition().equals(h.absolutePos(ore).above()),
                    "companion stayed on the ore support while mining it");
        });
    }

    @GameTest(maxTicks = 100)
    public void inventoryFullStopsBeforeOreExtraction(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        BlockPos ore = new BlockPos(5, 2, 3);
        h.setBlock(ore, Blocks.DIAMOND_ORE);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 3);
        h.assertTrue(
                GameTestSupport.observeVisible(h, companion, h.absolutePos(ore)),
                "diamond ore should be directly visible");
        companion.setItemSlot(
                net.minecraft.world.entity.EquipmentSlot.MAINHAND, Items.IRON_PICKAXE.getDefaultInstance());
        for (int i = 0; i < companion.inventory().getContainerSize(); i++) {
            companion.inventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        }
        Outcome outcome = new Outcome();
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 300, () -> false);
        runner.setEventSink(outcome);
        runner.start(
                new MineBlocksTask(BlockMatcher.parse("minecraft:diamond_ore").orElseThrow(), 1, 8), "mine_blocks");

        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(outcome.status == TaskStatus.FAILED, "full inventory did not stop mining");
            h.assertTrue(
                    h.getLevel().getBlockState(h.absolutePos(ore)).is(Blocks.DIAMOND_ORE),
                    "ore was destroyed before checking room for drops");
            h.assertTrue(outcome.summary.contains("inventory full"), "failure did not report full inventory");
        });
    }

    @GameTest(maxTicks = 300)
    public void stoneWithoutPickaxeRefusesInsteadOfMiningByHand(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        BlockPos stone = new BlockPos(4, 2, 2);
        h.setBlock(stone, Blocks.STONE);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.assertTrue(
                GameTestSupport.observeVisible(h, companion, h.absolutePos(stone)),
                "the stone should be directly visible");
        Outcome outcome = new Outcome();
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 300, () -> false);
        runner.setEventSink(outcome);
        runner.start(new MineBlocksTask(BlockMatcher.parse("minecraft:stone").orElseThrow(), 1, 8), "mine_blocks");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(outcome.status != null, "mining should finish");
            h.assertTrue(outcome.status == TaskStatus.FAILED, "status " + outcome.status + " " + outcome.summary);
            h.assertTrue(
                    outcome.summary.contains("no suitable tool"),
                    "the refusal must name the missing tool: " + outcome.summary);
            h.assertTrue(
                    h.getLevel().getBlockState(h.absolutePos(stone)).is(Blocks.STONE),
                    "stone must not be destroyed without a pickaxe");
        });
    }

    @GameTest(maxTicks = 2400)
    public void minesTwentyStoneBlocksFromAHill(GameTestHelper h) {
        GameTestSupport.platformAt(h, 120, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 121, 6);
        companion.inventory().addItem(Items.STONE_PICKAXE.getDefaultInstance());
        for (int x = 4; x <= 11; x++) {
            for (int z = 3; z <= 9; z++) {
                for (int y = 121; y <= 124; y++) h.setBlock(x, y, z, Blocks.STONE);
            }
        }
        h.assertTrue(
                GameTestSupport.observeVisible(h, companion, h.absolutePos(new BlockPos(4, 122, 6))),
                "the initial exposed stone should be visible");
        Outcome outcome = new Outcome();
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 300, () -> false);
        runner.setEventSink(outcome);
        runner.start(new MineBlocksTask(BlockMatcher.parse("minecraft:stone").orElseThrow(), 20, 16), "mine_blocks");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(
                    outcome.status == TaskStatus.SUCCESS,
                    "hill stone mining did not finish: " + outcome.status + " " + outcome.summary + ", cobblestone="
                            + companion.inventory().countItem(Items.COBBLESTONE));
        });
    }

    @GameTest(maxTicks = 2400)
    public void reachesIronHighOnAHillFaceThroughAStaircase(GameTestHelper h) {
        GameTestSupport.platformAt(h, 120, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 121, 6);
        companion.inventory().addItem(Items.IRON_PICKAXE.getDefaultInstance());
        for (int x = 4; x <= 11; x++) {
            for (int z = 3; z <= 9; z++) {
                for (int y = 121; y <= 127; y++) h.setBlock(x, y, z, Blocks.STONE);
            }
        }
        BlockPos ore = new BlockPos(4, 127, 6);
        h.setBlock(ore, Blocks.IRON_ORE);
        h.assertTrue(
                GameTestSupport.observeVisible(h, companion, h.absolutePos(ore)),
                "the iron ore high on the hill face should be directly visible");
        Outcome outcome = new Outcome();
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 300, () -> false);
        runner.setEventSink(outcome);
        runner.start(new MineBlocksTask(BlockMatcher.parse("minecraft:iron_ore").orElseThrow(), 1, 16), "mine_blocks");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(
                    outcome.status == TaskStatus.SUCCESS,
                    "iron was not mined: " + outcome.status + " " + outcome.summary + ", at "
                            + companion.blockPosition().toShortString());
        });
    }

    @GameTest(maxTicks = 3000)
    public void reachesIronBuriedBelowTheStandingPointThroughAStaircase(GameTestHelper h) {
        GameTestSupport.platformAt(h, 120, 16);
        for (int x = 0; x <= 11; x++) {
            for (int z = 3; z <= 9; z++) {
                for (int y = 121; y <= 130; y++) h.setBlock(x, y, z, Blocks.STONE);
            }
        }
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 131, 6);
        companion.inventory().addItem(Items.IRON_PICKAXE.getDefaultInstance());
        companion.setOwner(java.util.UUID.randomUUID());
        BlockPos ore = new BlockPos(7, 124, 6);
        h.setBlock(ore, Blocks.IRON_ORE);
        BlockPos absOre = h.absolutePos(ore);
        BlockPos at = companion.blockPosition();
        GameTestSupport.runtime(h.getLevel().getServer())
                .resourceCandidates()
                .observe(
                        companion,
                        new dev.friendai.features.knowledge.ResourceObservation(
                                "minecraft:iron_ore",
                                new dev.friendai.features.knowledge.ResourceObservation.Location(
                                        h.getLevel().dimension().identifier().toString(),
                                        new Pos(absOre.getX(), absOre.getY(), absOre.getZ())),
                                new Pos(at.getX(), at.getY(), at.getZ()),
                                h.getLevel().getServer().overworld().getGameTime()));
        Outcome outcome = new Outcome();
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 300, () -> false);
        runner.setEventSink(outcome);
        runner.start(new MineBlocksTask(BlockMatcher.parse("minecraft:iron_ore").orElseThrow(), 1, 16), "mine_blocks");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(
                    outcome.status == TaskStatus.SUCCESS,
                    "iron was not mined: " + outcome.status + " " + outcome.summary + ", at "
                            + companion.blockPosition().toShortString());
        });
    }

    @GameTest(maxTicks = 2400)
    public void miningDrawsTheWholePlannedPassage(GameTestHelper h) {
        GameTestSupport.platformAt(h, 130, 10);
        for (int x = 3; x <= 5; x++) {
            for (int z = 3; z <= 7; z++) {
                for (int y = 131; y <= 132; y++) h.setBlock(x, y, z, Blocks.STONE);
            }
        }
        BlockPos ore = new BlockPos(6, 131, 5);
        h.setBlock(ore, Blocks.IRON_ORE);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 1, 131, 5);
        companion.inventory().addItem(Items.IRON_PICKAXE.getDefaultInstance());
        companion.setOwner(java.util.UUID.randomUUID());
        BlockPos absOre = h.absolutePos(ore);
        BlockPos at = companion.blockPosition();
        GameTestSupport.runtime(h.getLevel().getServer())
                .resourceCandidates()
                .observe(
                        companion,
                        new dev.friendai.features.knowledge.ResourceObservation(
                                "minecraft:iron_ore",
                                new dev.friendai.features.knowledge.ResourceObservation.Location(
                                        h.getLevel().dimension().identifier().toString(),
                                        new Pos(absOre.getX(), absOre.getY(), absOre.getZ())),
                                new Pos(at.getX(), at.getY(), at.getZ()),
                                h.getLevel().getServer().overworld().getGameTime()));
        Outcome outcome = new Outcome();
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 300, () -> false);
        runner.setEventSink(outcome);
        runner.start(new MineBlocksTask(BlockMatcher.parse("minecraft:iron_ore").orElseThrow(), 1, 10), "mine_blocks");
        boolean[] passageShown = {false};
        h.succeedWhen(() -> {
            runner.tick();
            runner.current().ifPresent(task -> {
                for (DebugTarget target : task.debugTargets()) {
                    passageShown[0] |= target.detail().equals("Path obstacle");
                }
            });
            h.assertTrue(outcome.status != null, "mining did not finish at " + companion.blockPosition());
            h.assertTrue(
                    outcome.status == TaskStatus.SUCCESS,
                    "iron was not mined: " + outcome.status + " " + outcome.summary);
            h.assertTrue(passageShown[0], "the overlay never showed the cells of the planned passage");
        });
    }

    @GameTest(maxTicks = 400)
    public void stoneBrokenByHandDropsNothing(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.setBlock(new BlockPos(4, 2, 2), Blocks.STONE);
        BlockPos target = h.absolutePos(new BlockPos(4, 2, 2));
        BreakAction action = new BreakAction(target);
        TaskContext context = new TaskContext(companion, h.getLevel());
        long before = cobbleDrops(h);

        h.succeedWhen(() -> {
            BreakAction.State state = action.tick(context, block -> !block.isAir());
            h.assertTrue(state == BreakAction.State.BROKEN, "still breaking: " + state);
            h.assertTrue(h.getLevel().getBlockState(target).isAir(), "stone was not removed");
            h.assertTrue(cobbleDrops(h) == before, "stone dropped cobblestone by hand");
        });
    }

    @GameTest(maxTicks = 400)
    public void stoneBrokenWithPickaxeDropsCobblestone(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(Items.IRON_PICKAXE.getDefaultInstance());
        h.setBlock(new BlockPos(4, 2, 2), Blocks.STONE);
        BlockPos target = h.absolutePos(new BlockPos(4, 2, 2));
        BreakAction action = new BreakAction(target);
        TaskContext context = new TaskContext(companion, h.getLevel());
        long before = cobbleDrops(h);

        h.succeedWhen(() -> {
            BreakAction.State state = action.tick(context, block -> !block.isAir());
            h.assertTrue(state == BreakAction.State.BROKEN, "still breaking: " + state);
            h.assertTrue(cobbleDrops(h) == before + 1, "no cobblestone dropped");
        });
    }

    /**
     * The catch block is either gone, or kept because the companion stands on it and the drop below would hurt it -
     * the documented "load-bearing" case the cleanup reports instead of breaking.
     */
    private static boolean removedOrLoadBearing(GameTestHelper h, BlockPos below, String summary) {
        if (h.getLevel().getBlockState(below).isAir()) {
            return true;
        }
        // The one allowed leftover: the block the companion's footprint occupies over a drop that would hurt it,
        // which the summary names instead of removing it.
        return summary.contains("kept 1 temporary block") || summary.contains("kept 1 catch block");
    }

    /** Every diamond the companion still has or the world still holds: the drop must not be lost, taken or not. */
    private static int diamonds(GameTestHelper h, CompanionEntity companion) {
        int inWorld = h
                .getLevel()
                .getEntitiesOfClass(
                        net.minecraft.world.entity.item.ItemEntity.class,
                        new net.minecraft.world.phys.AABB(h.absolutePos(new BlockPos(0, 0, 0))).inflate(32),
                        e -> e.getItem().is(Items.DIAMOND))
                .stream()
                .mapToInt(e -> e.getItem().getCount())
                .sum();
        return companion.inventory().countItem(Items.DIAMOND) + inWorld;
    }

    private static long cobbleDrops(GameTestHelper h) {
        return h
                .getLevel()
                .getEntitiesOfClass(
                        net.minecraft.world.entity.item.ItemEntity.class,
                        new net.minecraft.world.phys.AABB(h.absolutePos(new BlockPos(0, 0, 0))).inflate(16),
                        e -> e.getItem().is(Items.COBBLESTONE))
                .stream()
                .mapToLong(e -> e.getItem().getCount())
                .sum();
    }

    private static ExcavationGuard guard(CompanionEntity companion, GameTestHelper h) {
        BlockPos start = companion.blockPosition();
        return new ExcavationGuard(new MiningBounds(new Node(start.getX(), start.getY(), start.getZ()), 8), Set.of());
    }

    // ---- item 33: a valuable drop must have somewhere to land -------------------------------------------------

    @GameTest(maxTicks = 2400)
    public void valuableOreOverLavaGetsACatchBlockAndKeepsItsDrop(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        // A lava lake, two cells deep and wide enough that a drifting drop cannot land on a neighbouring block.
        // An item takes four damage per lava tick and has five health, so one cell would only hurt it.
        for (int x = 3; x <= 5; x++) {
            for (int z = 1; z <= 3; z++) {
                h.setBlock(new BlockPos(x, 1, z), Blocks.LAVA);
                h.setBlock(new BlockPos(x, 0, z), Blocks.LAVA);
            }
        }
        h.setBlock(new BlockPos(4, 2, 2), Blocks.DIAMOND_ORE);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(Items.IRON_PICKAXE.getDefaultInstance());
        companion.inventory().addItem(new ItemStack(Items.DIRT, 16));
        BlockPos below = h.absolutePos(new BlockPos(4, 1, 2));
        Outcome outcome = new Outcome();
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 400, () -> false);
        runner.setEventSink(outcome);
        runner.start(new MineBlockAtTask(h.absolutePos(new BlockPos(4, 2, 2))), "mine_block_at");

        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(outcome.status != null, "mining did not finish: " + outcome.summary);
            h.assertTrue(
                    outcome.status == TaskStatus.SUCCESS,
                    "the ore over lava was not mined: " + outcome.status + " " + outcome.summary);
            h.assertTrue(
                    companion.inventory().countItem(Items.DIAMOND) == 1,
                    "the diamond burned instead of landing on the catch block: " + outcome.summary);
            h.assertTrue(
                    removedOrLoadBearing(h, below, outcome.summary),
                    "the catch block was left behind: " + h.getLevel().getBlockState(below) + " / " + outcome.summary);
            h.assertTrue(companion.inventory().countItem(Items.DIRT) < 16, "no catch block was placed at all");
        });
    }

    @GameTest(maxTicks = 2400)
    public void valuableOreOverARavineIsCaughtAndTheBlockIsTakenBack(GameTestHelper h) {
        GameTestSupport.platformAt(h, 20, 8);
        // Eight cells of air under the ore, as over a ravine: far below the sweep's reach.
        for (int y = 20; y >= 13; y--) {
            h.setBlock(new BlockPos(4, y, 2), Blocks.AIR);
        }
        h.setBlock(new BlockPos(4, 21, 2), Blocks.DIAMOND_ORE);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 21, 2);
        companion.inventory().addItem(Items.IRON_PICKAXE.getDefaultInstance());
        companion.inventory().addItem(new ItemStack(Items.DIRT, 8));
        BlockPos below = h.absolutePos(new BlockPos(4, 20, 2));
        Outcome outcome = new Outcome();
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 400, () -> false);
        runner.setEventSink(outcome);
        runner.start(new MineBlockAtTask(h.absolutePos(new BlockPos(4, 21, 2))), "mine_block_at");

        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(
                    outcome.status == TaskStatus.SUCCESS,
                    "the ore over the ravine was not mined: " + outcome.status + " " + outcome.summary);
            h.assertTrue(diamonds(h, companion) == 1, "the diamond fell into the ravine: " + outcome.summary);
            h.assertTrue(
                    removedOrLoadBearing(h, below, outcome.summary),
                    "the catch block was left behind: " + h.getLevel().getBlockState(below) + " / " + outcome.summary);
            h.assertTrue(companion.inventory().countItem(Items.DIRT) < 16, "no catch block was placed at all");
        });
    }

    @GameTest(maxTicks = 1200)
    public void valuableOreWithoutBuildingMaterialIsLeftUntouched(GameTestHelper h) {
        GameTestSupport.platformAt(h, 20, 8);
        for (int y = 20; y >= 13; y--) {
            h.setBlock(new BlockPos(4, y, 2), Blocks.AIR);
        }
        h.setBlock(new BlockPos(4, 21, 2), Blocks.DIAMOND_ORE);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 21, 2);
        companion.inventory().addItem(Items.IRON_PICKAXE.getDefaultInstance());
        BlockPos ore = h.absolutePos(new BlockPos(4, 21, 2));
        Outcome outcome = new Outcome();
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 400, () -> false);
        runner.setEventSink(outcome);
        runner.start(new MineBlockAtTask(ore), "mine_block_at");

        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(outcome.status != null, "mining did not finish: " + outcome.summary);
            h.assertTrue(
                    outcome.status == TaskStatus.FAILED,
                    "mining an unprotected drop was not refused: " + outcome.status + " " + outcome.summary);
            h.assertTrue(
                    h.getLevel().getBlockState(ore).is(Blocks.DIAMOND_ORE),
                    "the ore was mined although the drop had nowhere to land");
            h.assertTrue(
                    outcome.summary.contains("catch block"),
                    "the refusal must say what was missing: " + outcome.summary);
        });
    }

    @GameTest(maxTicks = 1200)
    public void aCatchBlockIsNeverBuiltInAProtectedPlace(GameTestHelper h) {
        GameTestSupport.platformAt(h, 20, 8);
        // Eight cells of air under the ore, as over a ravine: far below the sweep's reach.
        for (int y = 20; y >= 13; y--) {
            h.setBlock(new BlockPos(4, y, 2), Blocks.AIR);
        }
        h.setBlock(new BlockPos(4, 21, 2), Blocks.DIAMOND_ORE);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 21, 2);
        companion.inventory().addItem(Items.IRON_PICKAXE.getDefaultInstance());
        companion.inventory().addItem(new ItemStack(Items.DIRT, 8));
        BlockPos below = h.absolutePos(new BlockPos(4, 20, 2));
        BlockPos ore = h.absolutePos(new BlockPos(4, 21, 2));
        companion.hands().overrideProtection(pos -> pos.equals(below));
        Outcome outcome = new Outcome();
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 400, () -> false);
        runner.setEventSink(outcome);
        runner.start(new MineBlockAtTask(ore), "mine_block_at");

        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(outcome.status != null, "mining did not finish: " + outcome.summary);
            h.assertTrue(
                    outcome.status == TaskStatus.FAILED,
                    "the protected catch cell was mined around: " + outcome.status + " " + outcome.summary);
            h.assertTrue(h.getLevel().getBlockState(below).isAir(), "a block appeared in the protected cell");
            h.assertTrue(h.getLevel().getBlockState(ore).is(Blocks.DIAMOND_ORE), "the ore was mined anyway");
        });
    }

    @GameTest(maxTicks = 1200)
    public void ordinaryStoneOverAHoleIsMinedWithoutAPlatform(GameTestHelper h) {
        GameTestSupport.platformAt(h, 20, 8);
        h.setBlock(new BlockPos(4, 20, 2), Blocks.AIR);
        h.setBlock(new BlockPos(4, 21, 2), Blocks.STONE);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 21, 2);
        companion.inventory().addItem(Items.IRON_PICKAXE.getDefaultInstance());
        companion.inventory().addItem(new ItemStack(Items.DIRT, 8));
        BlockPos below = h.absolutePos(new BlockPos(4, 20, 2));
        Outcome outcome = new Outcome();
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 400, () -> false);
        runner.setEventSink(outcome);
        runner.start(new MineBlockAtTask(h.absolutePos(new BlockPos(4, 21, 2))), "mine_block_at");

        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(outcome.status != null, "mining did not finish: " + outcome.summary);
            h.assertTrue(
                    outcome.status == TaskStatus.SUCCESS,
                    "ordinary stone was slowed down: " + outcome.status + " " + outcome.summary);
            h.assertTrue(companion.inventory().countItem(Items.DIRT) == 8, "a platform was built for ordinary stone");
            h.assertTrue(h.getLevel().getBlockState(below).isAir(), "a block appeared under ordinary stone");
        });
    }

    @GameTest(maxTicks = 2400)
    public void plannedMiningPathBuildsTheCatchPlatformToo(GameTestHelper h) {
        GameTestSupport.platformAt(h, 20, 8);
        for (int y = 20; y >= 13; y--) {
            h.setBlock(new BlockPos(4, y, 2), Blocks.AIR);
        }
        h.setBlock(new BlockPos(4, 21, 2), Blocks.DIAMOND_ORE);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 21, 2);
        companion.inventory().addItem(Items.IRON_PICKAXE.getDefaultInstance());
        companion.inventory().addItem(new ItemStack(Items.DIRT, 8));
        BlockPos ore = h.absolutePos(new BlockPos(4, 21, 2));
        BlockPos below = h.absolutePos(new BlockPos(4, 20, 2));
        h.assertTrue(GameTestSupport.observeVisible(h, companion, ore), "the ore should be directly visible");
        Outcome outcome = new Outcome();
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 400, () -> false);
        runner.setEventSink(outcome);
        runner.start(
                new MineBlocksTask(BlockMatcher.parse("minecraft:diamond_ore").orElseThrow(), 1, 8), "mine_blocks");

        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(outcome.status != null, "mining did not finish: " + outcome.summary);
            h.assertTrue(
                    outcome.status == TaskStatus.SUCCESS,
                    "the planned path did not mine the ore: " + outcome.status + " " + outcome.summary);
            h.assertTrue(diamonds(h, companion) == 1, "the planned path lost the diamond: " + outcome.summary);
            h.assertTrue(
                    removedOrLoadBearing(h, below, outcome.summary),
                    "the catch block was left behind: " + h.getLevel().getBlockState(below) + " / " + outcome.summary);
            h.assertTrue(companion.inventory().countItem(Items.DIRT) < 16, "no catch block was placed at all");
            h.assertTrue(companion.inventory().countItem(Items.DIRT) < 16, "no catch block was placed at all");
        });
    }
}
