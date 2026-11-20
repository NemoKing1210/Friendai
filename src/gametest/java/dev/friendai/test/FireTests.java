package dev.friendai.test;

import com.google.gson.JsonObject;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.features.crafting.Station;
import dev.friendai.features.fire.FireTarget;
import dev.friendai.features.fire.minecraft.FireReflex;
import dev.friendai.features.fire.minecraft.task.ExtinguishFireTask;
import dev.friendai.foundation.spatial.Pos;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.server.execution.TaskEventSink;
import dev.friendai.task.TaskStatus;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/** Fires: dousing a block, the bucket coming back, the portal frame, the companion's own fire and its campfire. */
public class FireTests {

    private static final class Result implements TaskEventSink {
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

    private static ExecutionCoordinator run(CompanionEntity c, Result result, ExtinguishFireTask task) {
        ExecutionCoordinator runner = new ExecutionCoordinator(c, () -> ExtinguishFireTask.TIMEOUT_TICKS, () -> false);
        runner.setEventSink(result);
        runner.start(task, "extinguish_fire");
        return runner;
    }

    private static Pos absolute(GameTestHelper h, int x, int y, int z) {
        BlockPos pos = h.absolutePos(new BlockPos(x, y, z));
        return Pos.of(pos.getX(), pos.getY(), pos.getZ());
    }

    @GameTest(maxTicks = 400)
    public void dousesABurningBlockAndRecoversTheBucket(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        h.setBlock(3, 1, 2, Blocks.NETHERRACK);
        h.setBlock(3, 2, 2, Blocks.FIRE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.WATER_BUCKET));
        c.inventory().addItem(new ItemStack(Items.BUCKET));
        BlockPos fire = h.absolutePos(new BlockPos(3, 2, 2));
        Result result = new Result();
        ExecutionCoordinator runner =
                run(c, result, new ExtinguishFireTask(new FireTarget.Block(absolute(h, 3, 2, 2)), 8));

        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "the task did not finish");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(
                    h.getLevel().getBlockState(fire).isAir(),
                    "fire left behind: " + h.getLevel().getBlockState(fire));
            h.assertTrue(!h.getLevel().getFluidState(fire).getType().isSame(Fluids.WATER), "water left behind");
            h.assertTrue(
                    c.inventory().countItem(Items.WATER_BUCKET) == 1,
                    "water buckets back: " + c.inventory().countItem(Items.WATER_BUCKET));
            h.assertTrue(
                    c.inventory().countItem(Items.BUCKET) == 1,
                    "empty buckets left: " + c.inventory().countItem(Items.BUCKET));
        });
    }

    @GameTest(maxTicks = 400)
    public void doesNotTouchAFireInsideAPortalFrame(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        for (int y = 2; y <= 4; y++) {
            h.setBlock(2, y, 2, Blocks.OBSIDIAN);
            h.setBlock(4, y, 2, Blocks.OBSIDIAN);
        }
        h.setBlock(3, 2, 2, Blocks.OBSIDIAN);
        h.setBlock(3, 4, 2, Blocks.OBSIDIAN);
        h.setBlock(3, 3, 2, Blocks.FIRE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 7, 2, 2);
        c.inventory().addItem(new ItemStack(Items.WATER_BUCKET));
        BlockPos fire = h.absolutePos(new BlockPos(3, 3, 2));
        BlockPos frame = h.absolutePos(new BlockPos(2, 3, 2));
        Result result = new Result();
        ExecutionCoordinator runner =
                run(c, result, new ExtinguishFireTask(new FireTarget.Block(absolute(h, 3, 3, 2)), 8));

        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "the task did not finish");
            h.assertTrue(result.status == TaskStatus.FAILED, "status " + result.status + ": " + result.summary);
            h.assertTrue(result.summary.contains("portal frame"), "reason: " + result.summary);
            h.assertTrue(h.getLevel().getBlockState(fire).is(Blocks.FIRE), "the fire was put out anyway");
            h.assertTrue(h.getLevel().getBlockState(frame).is(Blocks.OBSIDIAN), "the frame was damaged");
            h.assertTrue(
                    c.inventory().countItem(Items.WATER_BUCKET) == 1,
                    "the bucket was spent: " + c.inventory().countItem(Items.WATER_BUCKET));
        });
    }

    @GameTest(maxTicks = 400)
    public void dousesItsOwnFire(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.WATER_BUCKET));
        c.setRemainingFireTicks(200);
        // On its own the fire would burn for 200 ticks; the reflex has to douse it long before that.
        h.runAfterDelay(
                40, () -> h.assertTrue(!c.isOnFire(), "still burning after 40 ticks: the fire reflex did not run"));

        h.succeedWhen(() -> {
            h.assertTrue(!c.isOnFire(), "the companion is still on fire");
            h.assertTrue(
                    h.getLevel().getBlockState(c.blockPosition()).isAir(),
                    "water left at its feet: " + h.getLevel().getBlockState(c.blockPosition()));
            h.assertTrue(
                    c.inventory().countItem(Items.WATER_BUCKET) == 1,
                    "the bucket came back: " + c.inventory().countItem(Items.WATER_BUCKET));
        });
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 100)
    public void verifiesTheSameBurningMobAfterItMoves(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 3, 2, 3);
        Pig target = burningPig(h);
        Result result = startEntityDouse(h, companion);
        BlockPos destination = h.absolutePos(new BlockPos(9, 2, 3));
        target.snapTo(destination.getX() + 0.5, destination.getY(), destination.getZ() + 0.5);
        Pig decoy = h.spawn(EntityTypes.PIG, 4, 2, 3);
        decoy.setNoAi(true);

        h.runAfterDelay(
                6,
                () -> h.assertFalse(
                        result.status == TaskStatus.SUCCESS && target.isOnFire(),
                        "the tool reported success for the unlit mob at the original position"));
        h.succeedWhen(() -> {
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertFalse(target.isOnFire(), "the selected pig is still burning");
            h.assertTrue(target.isAlive(), "the selected pig must still be alive");
            h.assertTrue(companion.inventory().countItem(Items.WATER_BUCKET) == 1, "the bucket was not recovered");
        });
    }

    @GameTest(maxTicks = 60)
    public void reportsLostBurningMobInsteadOfSuccessWhenItDisappears(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 3, 2, 3);
        Pig target = burningPig(h);
        Result result = startEntityDouse(h, companion);
        target.discard();

        h.succeedWhen(() -> assertLostEntity(h, companion, result));
    }

    @GameTest(maxTicks = 100)
    public void takesBackItsWaterWhenTheBurningMobDisappearsAfterThePour(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 3, 2, 3);
        Pig target = burningPig(h);
        Result result = startEntityDouse(h, companion);

        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(
                        companion.inventory().countItem(Items.BUCKET) == 1, "wait for the water to be poured"))
                .thenExecute(target::discard)
                .thenWaitUntil(() -> assertLostEntity(h, companion, result))
                .thenSucceed();
    }

    @GameTest(maxTicks = 60)
    public void reportsUnrecoveredWaterWhenThePouredSourceIsRemoved(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 3, 2, 3);
        Pig target = burningPig(h);
        Result result = startEntityDouse(h, companion);
        h.startSequence()
                .thenWaitUntil(
                        () -> h.assertTrue(companion.inventory().countItem(Items.BUCKET) == 1, "wait for the pour"))
                .thenExecute(() -> {
                    for (int x = 1; x <= 6; x++) {
                        for (int z = 1; z <= 6; z++) {
                            BlockPos cell = h.absolutePos(new BlockPos(x, 2, z));
                            if (h.getLevel().getFluidState(cell).getType().isSame(Fluids.WATER)) {
                                h.getLevel().setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
                            }
                        }
                    }
                    target.clearFire();
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(result.status != null, "wait for the final verification");
                    h.assertTrue(result.status == TaskStatus.SUCCESS, result.summary);
                    h.assertTrue(result.summary.contains("water was not recovered"), result.summary);
                    h.assertTrue(companion.inventory().countItem(Items.BUCKET) == 1, "the empty bucket stays carried");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 60)
    public void reportsLostBurningMobWhenItChangesDimension(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 3, 2, 3);
        Pig target = burningPig(h);
        Result result = startEntityDouse(h, companion);
        var nether = h.getLevel().getServer().getLevel(Level.NETHER);
        var moved = target.teleport(new TeleportTransition(
                nether, new Vec3(0.5, 64, 0.5), Vec3.ZERO, 0.0F, 0.0F, TeleportTransition.DO_NOTHING));
        h.assertTrue(moved instanceof Pig && moved.isOnFire(), "the burning pig must reach the other dimension");

        h.runAfterDelay(20, moved::discard);
        h.succeedWhen(() -> {
            h.assertTrue(moved.isAlive() && moved.level() == nether, "the target is alive in another dimension");
            assertLostEntity(h, companion, result);
            moved.discard();
        });
    }

    private static Pig burningPig(GameTestHelper h) {
        Pig target = h.spawn(EntityTypes.PIG, 4, 2, 3);
        target.setNoAi(true);
        target.setRemainingFireTicks(400);
        return target;
    }

    private static Result startEntityDouse(GameTestHelper h, CompanionEntity companion) {
        companion.inventory().addItem(new ItemStack(Items.WATER_BUCKET));
        Result result = new Result();
        companion.executionCoordinator().setEventSink(result);
        JsonObject args = new JsonObject();
        args.addProperty("target", "entity");
        var started = GameTestToolRegistry.create(companion, () -> 8)
                .bind(companion)
                .execute(new ToolCall("1", "extinguish_fire", args, args.toString(), null));
        h.assertFalse(started.error(), started.content());
        return result;
    }

    private static void assertLostEntity(GameTestHelper h, CompanionEntity companion, Result result) {
        h.assertTrue(result.status == TaskStatus.FAILED, "status " + result.status + ": " + result.summary);
        h.assertTrue(result.summary.contains("lost the burning mob"), "reason: " + result.summary);
        h.assertTrue(companion.inventory().countItem(Items.WATER_BUCKET) == 1, "the attempt spent the bucket");
    }

    @GameTest(maxTicks = 600)
    public void nearDousesEveryFireItCanReachEvenPastARefusedOne(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        h.setBlock(3, 1, 2, Blocks.NETHERRACK);
        h.setBlock(3, 2, 2, Blocks.FIRE);
        h.setBlock(5, 1, 2, Blocks.NETHERRACK);
        h.setBlock(5, 2, 2, Blocks.FIRE);
        h.setBlock(4, 1, 2, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.WATER_BUCKET));
        BlockPos campfire = h.absolutePos(new BlockPos(4, 1, 2));
        c.stationLease().keep(Station.CAMPFIRE, campfire.asLong(), h.getLevel().getGameTime());
        BlockPos first = h.absolutePos(new BlockPos(3, 2, 2));
        BlockPos second = h.absolutePos(new BlockPos(5, 2, 2));
        Result result = new Result();
        ExecutionCoordinator runner = run(c, result, ExtinguishFireTask.near(8));

        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "the task did not finish");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(
                    h.getLevel().getBlockState(first).isAir(),
                    "the first fire is still there: " + h.getLevel().getBlockState(first));
            h.assertTrue(
                    h.getLevel().getBlockState(second).isAir(),
                    "the second fire is still there: " + h.getLevel().getBlockState(second));
            h.assertTrue(
                    h.getLevel().getBlockState(campfire).getValue(CampfireBlock.LIT),
                    "the cooking campfire was put out");
        });
    }

    @GameTest(maxTicks = 600)
    public void nearReachesTheCornerOfTheScanCube(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        h.setBlock(7, 1, 7, Blocks.NETHERRACK);
        h.setBlock(7, 2, 7, Blocks.FIRE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 1, 2, 1);
        BlockPos fire = h.absolutePos(new BlockPos(7, 2, 7));
        Result result = new Result();
        ExecutionCoordinator runner = run(c, result, ExtinguishFireTask.near(8));

        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "the task did not finish");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(
                    h.getLevel().getBlockState(fire).isAir(),
                    "the corner fire is still there: " + h.getLevel().getBlockState(fire));
        });
    }

    @GameTest(maxTicks = 400)
    public void takesTheWaterBackWhenStoppedMidDouse(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        h.setBlock(3, 1, 2, Blocks.NETHERRACK);
        h.setBlock(3, 2, 2, Blocks.FIRE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.WATER_BUCKET));
        c.inventory().addItem(new ItemStack(Items.BUCKET));
        BlockPos fire = h.absolutePos(new BlockPos(3, 2, 2));
        Result result = new Result();
        ExecutionCoordinator runner =
                run(c, result, new ExtinguishFireTask(new FireTarget.Block(absolute(h, 3, 2, 2)), 8));
        runner.tick();
        runner.stop("test stop");

        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(
                    h.getLevel().getFluidState(fire).isEmpty()
                            || !h.getLevel().getFluidState(fire).isSource(),
                    "a source was left behind: " + h.getLevel().getBlockState(fire));
            h.assertTrue(
                    c.inventory().countItem(Items.WATER_BUCKET) == 1,
                    "the bucket did not come back: " + c.inventory().countItem(Items.WATER_BUCKET));
        });
    }

    @GameTest(maxTicks = 100)
    public void doesNotPromiseTheOwnerAFireTaskWithoutWater(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        GameTestSupport.OnlinePlayer owner = GameTestSupport.onlinePlayer(h, "fire-owner", 3.5, 2, 2.5, true);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setOwner(owner.id());
        owner.player().setRemainingFireTicks(200);

        c.fireReflex().tick();

        h.assertTrue(owner.player().isOnFire(), "the owner stopped burning before the reflex ran");
        h.assertTrue(
                !c.taskExecution().currentToolName().equals(FireReflex.TOOL),
                "started a fire task with nothing to put it out with");
        String said = lastMessageKey(owner);
        h.assertTrue("friendai.fire.owner_cannot_help".equals(said), "the owner was not told it cannot help: " + said);
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void startsAFireTaskForABurningOwnerWhenItHasWater(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        GameTestSupport.OnlinePlayer owner = GameTestSupport.onlinePlayer(h, "fire-owner", 3.5, 2, 2.5, true);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setOwner(owner.id());
        c.inventory().addItem(new ItemStack(Items.WATER_BUCKET));
        owner.player().setRemainingFireTicks(200);

        c.fireReflex().tick();

        h.assertTrue(owner.player().isOnFire(), "the owner stopped burning before the reflex ran");
        h.assertTrue(
                c.taskExecution().currentToolName().equals(FireReflex.TOOL),
                "no fire task for a burning owner: " + c.taskExecution().currentToolName());
        String said = lastMessageKey(owner);
        h.assertTrue("friendai.fire.owner".equals(said), "the owner was not told it is being helped: " + said);
        h.succeed();
    }

    /** The translation key of the newest line the server sent this player. */
    private static String lastMessageKey(GameTestSupport.OnlinePlayer player) {
        List<Component> messages = player.drainSystemMessages();
        if (messages.isEmpty()) {
            return null;
        }
        Component message = messages.getLast();
        return message.getContents() instanceof TranslatableContents text ? text.getKey() : null;
    }

    @GameTest(maxTicks = 400)
    public void leavesTheCookingCampfireLit(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        h.setBlock(3, 1, 2, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.WATER_BUCKET));
        BlockPos campfire = h.absolutePos(new BlockPos(3, 1, 2));
        c.stationLease().keep(Station.CAMPFIRE, campfire.asLong(), h.getLevel().getGameTime());
        Result result = new Result();
        ExecutionCoordinator runner =
                run(c, result, new ExtinguishFireTask(new FireTarget.Block(absolute(h, 3, 1, 2)), 8));

        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "the task did not finish");
            h.assertTrue(result.status == TaskStatus.FAILED, "status " + result.status + ": " + result.summary);
            h.assertTrue(result.summary.contains("cooking station"), "reason: " + result.summary);
            h.assertTrue(
                    h.getLevel().getBlockState(campfire).getValue(CampfireBlock.LIT),
                    "the cooking campfire was put out");
            h.assertTrue(
                    c.inventory().countItem(Items.WATER_BUCKET) == 1,
                    "the bucket was spent: " + c.inventory().countItem(Items.WATER_BUCKET));
        });
    }
}
