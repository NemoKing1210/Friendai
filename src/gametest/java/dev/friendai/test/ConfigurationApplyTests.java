package dev.friendai.test;

import com.google.gson.JsonObject;
import dev.friendai.config.ServerConfig;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.server.configuration.ServerConfiguration;
import dev.friendai.server.configuration.ServerSettings;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.tool.builtin.FriendaiTools;
import dev.friendai.tool.builtin.MemoryAccess;
import dev.friendai.tool.builtin.TaskToolDef;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Verifies settings snapshots are captured by new tasks without changing work already constructed. */
public class ConfigurationApplyTests {

    @GameTest(maxTicks = 40)
    public void newMiningTasksUseUpdatedSearchRadius(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(UUID.randomUUID());
        companion.inventory().addItem(Items.IRON_PICKAXE.getDefaultInstance());

        BlockPos target = new BlockPos(10, 2, 2);
        h.setBlock(target, Blocks.ACACIA_LOG);
        h.assertTrue(
                GameTestSupport.observeVisible(h, companion, h.absolutePos(target)),
                "the distant log should be visible to the companion");

        ServerConfiguration configuration = new ServerConfiguration(ignored -> {});
        ServerConfig nearbyOnly = ServerConfig.defaults();
        nearbyOnly.tasks.mineSearchRadius = 4;
        configuration.apply(nearbyOnly);
        ServerSettings firstSnapshot = configuration.current();

        FriendaiTools.Builtins builtins = FriendaiTools.build(
                () -> configuration.current().tasks().mineSearchRadius(),
                () -> configuration.current().tasks().treeSearchRadius(),
                MemoryAccess.DISABLED);
        TaskToolDef mineBlocks = builtins.taskTools().get("mine_blocks");
        Caller caller = new Caller(companion.ownerUuid(), "test", AccessLevel.TRUSTED);
        Task existing = mineBlocks.callerFactory().create(companion, caller, mineArgs());
        TaskContext existingContext = context(companion, firstSnapshot);
        existing.start(existingContext);

        ServerConfig widerSearch = ServerConfig.defaults();
        widerSearch.tasks.mineSearchRadius = 12;
        configuration.apply(widerSearch);
        ServerSettings secondSnapshot = configuration.current();

        h.assertValueEqual(firstSnapshot.tasks().mineSearchRadius(), 4, "captured first radius");
        h.assertValueEqual(secondSnapshot.tasks().mineSearchRadius(), 12, "published second radius");
        h.assertTrue(firstSnapshot != secondSnapshot, "applying config publishes a new snapshot");
        TaskStatus existingStatus = existing.tick(existingContext);
        h.assertTrue(
                existingStatus == TaskStatus.FAILED,
                "the existing task keeps its original search radius: " + existing.resultSummary());

        Task next = mineBlocks.callerFactory().create(companion, caller, mineArgs());
        TaskContext nextContext = context(companion, secondSnapshot);
        next.start(nextContext);
        TaskStatus nextStatus = next.tick(nextContext);
        h.assertTrue(nextStatus == TaskStatus.RUNNING, "a new task searches with the updated radius");
        h.assertTrue(
                next.debugTargets().stream()
                        .anyMatch(debug -> debug.x() == h.absolutePos(target).getX()
                                && debug.y() == h.absolutePos(target).getY()
                                && debug.z() == h.absolutePos(target).getZ()),
                "the new task selected the block outside the previous search radius: " + next.debugTargets());

        existing.stop(existingContext);
        next.stop(nextContext);
        h.succeed();
    }

    private static TaskContext context(CompanionEntity companion, ServerSettings settings) {
        return new TaskContext(
                companion,
                (net.minecraft.server.level.ServerLevel) companion.level(),
                companion.executionCoordinator(),
                null,
                settings);
    }

    private static JsonObject mineArgs() {
        JsonObject args = new JsonObject();
        args.addProperty("block", "minecraft:acacia_log");
        args.addProperty("count", 1);
        args.addProperty("collect", false);
        return args;
    }
}
