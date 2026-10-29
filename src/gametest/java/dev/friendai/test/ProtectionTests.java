package dev.friendai.test;

import com.google.gson.JsonObject;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.queue.FailurePolicy;
import dev.friendai.execution.queue.GuardAction;
import dev.friendai.execution.queue.PlanOptions;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.features.mining.minecraft.mining.BlockMatcher;
import dev.friendai.features.mining.minecraft.task.MineBlockAtTask;
import dev.friendai.features.mining.minecraft.task.MineBlocksTask;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.features.places.PlaceKind;
import dev.friendai.features.places.PlaceSource;
import dev.friendai.features.places.PlaceValues;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.task.Task;
import dev.friendai.task.TaskListener;
import dev.friendai.task.TaskStatus;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * A refusal says which rule refused and what the companion can do instead, and a block the companion stands on
 * does not cost it the stone it was asked for.
 */
public class ProtectionTests {
    private static final class Result implements TaskListener {
        TaskStatus status;
        String summary;

        @Override
        public void onFinished(int id, String toolName, TaskStatus status, String summary) {
            this.status = status;
            this.summary = summary;
        }

        @Override
        public void onCancelled(int id, String toolName, String reason) {
            this.summary = "cancelled: " + reason;
        }

        @Override
        public void onJobFinished(long jobId, long planId, String toolName, TaskStatus status, String summary) {
            this.status = status;
            this.summary = summary;
        }
    }

    private record Run(ExecutionCoordinator runner, Result result) {}

    private static Run owner(ExecutionCoordinator runner) {
        Result result = new Result();
        runner.setListener(result);
        return new Run(runner, result);
    }

    /** Queues the task as free time's own, so the place guard applies to it; the entity ticks its own runner. */
    private static Run autonomous(CompanionEntity companion, Task task) {
        Result result = new Result();
        ExecutionCoordinator runner = companion.executionCoordinator();
        runner.setListener(result);
        QueuedStep step = new QueuedStep(
                task.type(), new JsonObject(), null, List.of(), GuardAction.WAIT, FailurePolicy.ASK, 1, List.of());
        long plan = runner.enqueuePlan(
                "protection", List.of(step), List.of(context -> task), new PlanOptions(-10, FailurePolicy.ASK, 0));
        runner.markAutonomousPlan(plan);
        return new Run(runner, result);
    }

    private static CompanionEntity companion(GameTestHelper h) {
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(UUID.randomUUID());
        return companion;
    }

    /** A companion standing on a floor at {@code floorY}, at its own height band so tests do not collide. */
    private static CompanionEntity companionAt(GameTestHelper h, int floorY) {
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, floorY + 1, 2);
        companion.setOwner(UUID.randomUUID());
        return companion;
    }

    private static void rememberHome(GameTestHelper h, CompanionEntity companion) {
        BlockPos anchor = companion.blockPosition();
        GameTestSupport.runtime(h.getLevel().getServer())
                .placeService()
                .remember(
                        companion,
                        new Caller(companion.ownerUuid(), "owner", AccessLevel.OWNER),
                        new PlaceValues(
                                "home",
                                PlaceKind.HOUSE,
                                "",
                                companion.level().dimension().identifier().toString(),
                                new Pos(anchor.getX(), anchor.getY(), anchor.getZ()),
                                4,
                                Set.of(),
                                PlaceSource.MANUAL,
                                companion.ownerUuid(),
                                ""),
                        companion.level().getGameTime());
    }

    @GameTest(maxTicks = 2400)
    public void placeProtectionLeavesTheStoneOutsideAndMinesIt(GameTestHelper h) {
        GameTestSupport.platformAt(h, 290, 16);
        CompanionEntity c = companionAt(h, 290);
        rememberHome(h, c);
        BlockPos inside = h.absolutePos(new BlockPos(5, 291, 3));
        BlockPos outside = h.absolutePos(new BlockPos(12, 291, 2));
        h.setBlock(new BlockPos(5, 291, 3), Blocks.DIORITE);
        h.setBlock(new BlockPos(12, 291, 2), Blocks.DIORITE);
        c.inventory().addItem(Items.IRON_PICKAXE.getDefaultInstance());
        h.assertTrue(GameTestSupport.observeVisible(h, c, inside), "the place block should be visible");
        h.assertTrue(GameTestSupport.observeVisible(h, c, outside), "the legal block should be visible");

        Run legal = autonomous(
                c, new MineBlocksTask(BlockMatcher.parse("minecraft:diorite").orElseThrow(), 1, 32));
        h.succeedWhen(() -> {
            h.assertTrue(
                    legal.result().status == TaskStatus.SUCCESS,
                    "a legal block outside the place must be mined: " + legal.result().status + " "
                            + legal.result().summary);
            h.assertTrue(
                    h.getBlockState(new BlockPos(5, 291, 3)).is(Blocks.DIORITE),
                    "the block inside the place must stay");
            h.assertTrue(h.getBlockState(new BlockPos(12, 291, 2)).isAir(), "the block outside the place must be gone");
        });
    }

    @GameTest(maxTicks = 2400)
    public void placeProtectionNamesThePlaceAndTheWayOut(GameTestHelper h) {
        GameTestSupport.platformAt(h, 290, 16);
        CompanionEntity c = companionAt(h, 290);
        rememberHome(h, c);
        BlockPos inside = h.absolutePos(new BlockPos(5, 291, 3));
        h.setBlock(new BlockPos(5, 291, 3), Blocks.DIORITE);
        h.assertTrue(GameTestSupport.observeVisible(h, c, inside), "the place block should be visible");

        Run refused = autonomous(c, new MineBlockAtTask(inside));
        h.succeedWhen(() -> {
            h.assertTrue(
                    refused.result().status == TaskStatus.FAILED,
                    "a place target must fail: " + refused.result().status);
            h.assertTrue(
                    refused.result().summary.contains("place \"home\""),
                    "the summary must name the place: " + refused.result().summary);
            h.assertTrue(
                    refused.result().summary.contains("mine outside home"),
                    "the summary must say the way out: " + refused.result().summary);
            h.assertTrue(h.getBlockState(new BlockPos(5, 291, 3)).is(Blocks.DIORITE), "the protected block must stay");
        });
    }

    @GameTest(maxTicks = 1200)
    public void serverProtectionNamesTheServerAndNotAPlace(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(new BlockPos(6, 2, 2), Blocks.PRISMARINE_BRICKS);
        CompanionEntity c = companion(h);
        BlockPos target = h.absolutePos(new BlockPos(6, 2, 2));
        c.hands().overrideProtection(pos -> pos.equals(target));
        Run run = owner(new ExecutionCoordinator(c, () -> 300, () -> false));
        run.runner().start(new MineBlockAtTask(target), "mine_block_at");
        h.succeedWhen(() -> {
            run.runner().tick();
            h.assertTrue(run.result().status == TaskStatus.FAILED, "not failed: " + run.result().status);
            h.assertTrue(
                    run.result().summary.contains("protected by the server"),
                    "the summary must name the server: " + run.result().summary);
            h.assertTrue(
                    !run.result().summary.contains("place"),
                    "the server refusal must not blame a place: " + run.result().summary);
            h.assertTrue(
                    h.getBlockState(new BlockPos(6, 2, 2)).is(Blocks.PRISMARINE_BRICKS),
                    "the protected block must stay");
        });
    }

    @GameTest(maxTicks = 1200)
    public void theBlockUnderTheCompanionIsMinedWhenTheDropIsSafe(GameTestHelper h) {
        GameTestSupport.platformAt(h, 310, 16);
        h.setBlock(new BlockPos(2, 309, 2), Blocks.STONE);
        CompanionEntity c = companionAt(h, 310);
        BlockPos target = h.absolutePos(new BlockPos(2, 310, 2));
        c.inventory().addItem(Items.IRON_PICKAXE.getDefaultInstance());
        Run run = owner(new ExecutionCoordinator(c, () -> 600, () -> false));
        run.runner().start(new MineBlockAtTask(target), "mine_block_at");
        h.succeedWhen(() -> {
            run.runner().tick();
            h.assertTrue(
                    run.result().status == TaskStatus.SUCCESS,
                    "the stone under the feet must be mined safely: " + run.result().status + " "
                            + run.result().summary);
            h.assertTrue(h.getBlockState(new BlockPos(2, 310, 2)).isAir(), "the target must be gone");
            h.assertTrue(c.inventory().countItem(Items.COBBLESTONE) >= 1, "the drop must be collected");
            h.assertTrue(
                    h.getBlockState(new BlockPos(2, 309, 2)).is(Blocks.STONE),
                    "the footing under the target must stay");
            h.assertTrue(c.getY() >= 310.0, "the companion must not fall into the hole: " + c.getY());
        });
    }

    @GameTest(maxTicks = 1200)
    public void theBlockUnderTheCompanionIsRefusedOverLava(GameTestHelper h) {
        GameTestSupport.platformAt(h, 310, 16);
        h.setBlock(new BlockPos(2, 309, 2), Blocks.LAVA);
        CompanionEntity c = companionAt(h, 310);
        BlockPos target = h.absolutePos(new BlockPos(2, 310, 2));
        c.inventory().addItem(Items.IRON_PICKAXE.getDefaultInstance());
        Run run = owner(new ExecutionCoordinator(c, () -> 600, () -> false));
        run.runner().start(new MineBlockAtTask(target), "mine_block_at");
        h.succeedWhen(() -> {
            run.runner().tick();
            h.assertTrue(
                    run.result().status == TaskStatus.FAILED,
                    "an unsafe drop must be refused: " + run.result().status + " " + run.result().summary);
            h.assertTrue(
                    run.result().summary.contains("standing on"),
                    "the summary must say what the block is: " + run.result().summary);
            h.assertTrue(
                    run.result().summary.contains("not safe"), "the summary must say why: " + run.result().summary);
            h.assertTrue(h.getBlockState(new BlockPos(2, 310, 2)).is(Blocks.STONE), "the block over lava must stay");
        });
    }
}
