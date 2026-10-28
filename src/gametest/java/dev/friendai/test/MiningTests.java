package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.features.mining.MiningBounds;
import dev.friendai.features.navigation.minecraft.nav.Sight;
import dev.friendai.features.navigation.pathing.Move;
import dev.friendai.features.navigation.pathing.Node;
import dev.friendai.features.navigation.pathing.PathStep;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskListener;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.builtin.MineBlocksTask;
import dev.friendai.task.mining.BlockMatcher;
import dev.friendai.task.mining.BreakAction;
import dev.friendai.task.mining.ExcavationGuard;
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

    private static final class Outcome implements TaskListener {
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
        runner.setListener(outcome);
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
        runner.setListener(outcome);
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
        runner.setListener(outcome);
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
        runner.setListener(outcome);
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
        runner.setListener(outcome);
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
        dev.friendai.core.FriendaiServerState.get(h.getLevel().getServer())
                .resources()
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
        runner.setListener(outcome);
        runner.start(new MineBlocksTask(BlockMatcher.parse("minecraft:iron_ore").orElseThrow(), 1, 16), "mine_blocks");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(
                    outcome.status == TaskStatus.SUCCESS,
                    "iron was not mined: " + outcome.status + " " + outcome.summary + ", at "
                            + companion.blockPosition().toShortString());
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
}
