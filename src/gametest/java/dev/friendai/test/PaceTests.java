package dev.friendai.test;

import com.google.gson.JsonParser;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.tool.ToolExecutor;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.navigation.minecraft.task.FollowTask;
import dev.friendai.features.navigation.minecraft.task.GoToTask;
import dev.friendai.features.navigation.movement.PaceMode;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * Walking versus running. The entity ticks its own task runner. Each test owns a world band of its own (platformAt)
 * and stays within about 12 blocks of its start along +X: a test box only keeps its own chunks active, and a companion
 * that walks out of them silently stops.
 */
public class PaceTests {
    @GameTest(maxTicks = 400)
    public void followRunsWhenOwnerPullsAway(GameTestHelper h) {
        int floor = 60;
        GameTestSupport.platformAt(h, floor, 20);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 12.0, floor + 1, 2.5);
        owner.setOnGround(true);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 1, floor + 1, 2);
        c.setOwner(owner.getUUID());
        c.executionCoordinator().start(new FollowTask(true), "follow");
        boolean[] sprinted = {false};
        h.succeedWhen(() -> {
            if (c.isSprinting()) {
                sprinted[0] = true;
            }
            h.assertTrue(sprinted[0], "never sprinted while the owner was far away");
            double distance = c.distanceTo(owner);
            h.assertTrue(distance <= 5.0, "still " + distance + " blocks from the owner");
            h.assertFalse(c.isSprinting(), "still sprinting " + distance + " blocks from the owner");
        });
    }

    @GameTest(maxTicks = 200)
    public void runModeSprintsOnGoTo(GameTestHelper h) {
        int floor = 70;
        GameTestSupport.platformAt(h, floor, 20);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, floor + 1, 2);
        c.setPaceMode(PaceMode.RUN);
        c.executionCoordinator().start(new GoToTask(h.absoluteVec(new Vec3(12.5, floor + 1, 2.5))), "go_to");
        h.succeedWhen(() -> h.assertTrue(c.isSprinting(), "not sprinting while walking to the target"));
    }

    @GameTest(maxTicks = 300)
    public void walkModeNeverSprints(GameTestHelper h) {
        int floor = 80;
        GameTestSupport.platformAt(h, floor, 20);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 12.0, floor + 1, 2.5);
        owner.setOnGround(true);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 1, floor + 1, 2);
        c.setOwner(owner.getUUID());
        c.setPaceMode(PaceMode.WALK);
        c.executionCoordinator().start(new FollowTask(true), "follow");
        Vec3 start = c.position();
        h.onEachTick(() -> h.assertFalse(c.isSprinting(), "sprinted in walk mode"));
        h.runAfterDelay(100, () -> {
            h.assertTrue(
                    c.position().distanceTo(start) > 5.0, "the companion barely moved, so the test proves nothing");
            h.succeed();
        });
    }

    @GameTest(maxTicks = 300)
    public void sprintClearsWhenIdle(GameTestHelper h) {
        int floor = 90;
        GameTestSupport.platformAt(h, floor, 20);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, floor + 1, 2);
        c.setPaceMode(PaceMode.RUN);
        Vec3 target = h.absoluteVec(new Vec3(11.5, floor + 1, 2.5));
        c.executionCoordinator().start(new GoToTask(target), "go_to");
        boolean[] sprinted = {false};
        long[] arrivedAt = {-1};
        h.succeedWhen(() -> {
            if (c.isSprinting()) {
                sprinted[0] = true;
            }
            h.assertTrue(sprinted[0], "never sprinted on the way");
            if (arrivedAt[0] < 0 && Math.abs(c.getX() - target.x) <= 1.5) {
                arrivedAt[0] = h.getTick();
            }
            h.assertTrue(arrivedAt[0] >= 0 && h.getTick() - arrivedAt[0] >= 5, "not arrived yet");
            h.assertFalse(c.isSprinting(), "still sprinting after arriving");
        });
    }

    @GameTest
    public void noSprintInWater(GameTestHelper h) {
        int floor = 110;
        GameTestSupport.platformAt(h, floor, 8);
        for (int x = 1; x <= 3; x++) {
            for (int z = 1; z <= 3; z++) {
                h.setBlock(x, floor + 1, z, Blocks.WATER);
                h.setBlock(x, floor + 2, z, Blocks.WATER);
            }
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, floor + 1, 2);
        c.setPaceMode(PaceMode.RUN);
        h.runAfterDelay(3, () -> {
            h.assertTrue(c.isInWater(), "the companion should be in water");
            h.assertFalse(c.canSprint(), "canSprint in water");
            c.setSprinting(true);
            c.pace().update(false, Double.NaN);
            h.assertFalse(c.isSprinting(), "sprinting in water");
            h.succeed();
        });
    }

    /** Two companions on side-by-side lanes race over the same distance; the runner needs well under 1/1.2 of the walker's time. */
    @GameTest(maxTicks = 300)
    public void runIsFasterThanWalk(GameTestHelper h) {
        int floor = 120;
        GameTestSupport.platformAt(h, floor, 20);
        CompanionEntity walker = h.spawn(FriendaiEntities.COMPANION, 2, floor + 1, 2);
        CompanionEntity runner = h.spawn(FriendaiEntities.COMPANION, 2, floor + 1, 5);
        walker.setPaceMode(PaceMode.WALK);
        runner.setPaceMode(PaceMode.RUN);
        double distance = 9.0;
        walker.executionCoordinator().start(new GoToTask(h.absoluteVec(new Vec3(14.5, floor + 1, 2.5))), "go_to");
        runner.executionCoordinator().start(new GoToTask(h.absoluteVec(new Vec3(14.5, floor + 1, 5.5))), "go_to");
        double walkerStart = walker.getX();
        double runnerStart = runner.getX();
        long[] walkerTicks = {-1};
        long[] runnerTicks = {-1};
        h.succeedWhen(() -> {
            if (walkerTicks[0] < 0 && walker.getX() - walkerStart >= distance) {
                walkerTicks[0] = h.getTick();
            }
            if (runnerTicks[0] < 0 && runner.getX() - runnerStart >= distance) {
                runnerTicks[0] = h.getTick();
            }
            h.assertTrue(walkerTicks[0] >= 0 && runnerTicks[0] >= 0, "not both there yet");
            System.out.println("PaceTests runIsFasterThanWalk ticks for " + distance + " blocks: walk=" + walkerTicks[0]
                    + " run=" + runnerTicks[0]);
            h.assertTrue(
                    walkerTicks[0] >= runnerTicks[0] * 1.2,
                    "walk took " + walkerTicks[0] + " ticks, run " + runnerTicks[0]);
        });
    }

    @GameTest
    public void paceModeSurvivesSave(GameTestHelper h) {
        CompanionEntity a = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        a.setPaceMode(PaceMode.RUN);
        TagValueOutput out = TagValueOutput.createWithContext(
                ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        a.saveWithoutId(out);
        CompoundTag tag = out.buildResult();

        CompanionEntity b = h.spawn(FriendaiEntities.COMPANION, 4, 2, 4);
        b.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), tag));
        h.assertTrue(b.paceMode() == PaceMode.RUN, "pace mode was " + b.paceMode());

        tag.remove("PaceMode");
        CompanionEntity d = h.spawn(FriendaiEntities.COMPANION, 6, 2, 6);
        d.setPaceMode(PaceMode.WALK);
        d.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), tag));
        h.assertTrue(d.paceMode() == PaceMode.AUTO, "a missing key should give auto, was " + d.paceMode());
        h.succeed();
    }

    @GameTest
    public void staleSprintFlagClearsWhenCannotSprint(GameTestHelper h) {
        int floor = 130;
        GameTestSupport.platformAt(h, floor, 8);
        for (int x = 1; x <= 3; x++) {
            for (int z = 1; z <= 3; z++) {
                h.setBlock(x, floor + 1, z, Blocks.WATER);
                h.setBlock(x, floor + 2, z, Blocks.WATER);
            }
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, floor + 1, 2);
        h.runAfterDelay(3, () -> {
            h.assertFalse(c.canSprint(), "canSprint in water");
            c.setSprinting(true);
        });
        h.runAfterDelay(6, () -> {
            h.assertFalse(c.isSprinting(), "the stale sprint flag survived a tick");
            h.succeed();
        });
    }

    @GameTest
    public void setPaceToolSwitchesMode(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ToolExecutor tools = GameTestToolRegistry.create(c, () -> 32).bind(c);
        String ok = "{\"mode\":\"run\"}";
        ToolResult r = tools.execute(
                new ToolCall("1", "set_pace", JsonParser.parseString(ok).getAsJsonObject(), ok, null));
        h.assertTrue(!r.error() && r.content().equals("pace: run"), "result was " + r.content());
        h.assertTrue(c.paceMode() == PaceMode.RUN, "mode was " + c.paceMode());
        String bad = "{\"mode\":\"sprint\"}";
        ToolResult e = tools.execute(
                new ToolCall("2", "set_pace", JsonParser.parseString(bad).getAsJsonObject(), bad, null));
        h.assertTrue(e.error(), "a bad mode should be an error, was " + e.content());
        h.assertTrue(c.paceMode() == PaceMode.RUN, "a bad mode changed the pace to " + c.paceMode());
        h.succeed();
    }
}
