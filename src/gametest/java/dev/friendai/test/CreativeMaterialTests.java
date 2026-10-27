package dev.friendai.test;

import dev.friendai.core.CreativeModeService;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.builtin.BuildTask;
import dev.friendai.task.builtin.ObtainTask;
import dev.friendai.task.builtin.PlaceBlockTask;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.Caller;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;

public final class CreativeMaterialTests {
    private static CompanionEntity creativeCompanion(GameTestHelper h, ServerPlayer owner) {
        owner.level().getServer().getPlayerList().op(owner.nameAndId(),
                Optional.of(LevelBasedPermissionSet.GAMEMASTER), Optional.empty());
        CompanionEntity companion = CompanionManager.get(h.getLevel().getServer()).summon(owner);
        h.assertTrue(CreativeModeService.requestMode(companion, owner, true) == CreativeModeService.Result.CHANGED,
                "operator owner could not enable creative mode");
        return companion;
    }

    private static Caller caller(ServerPlayer player) {
        return new Caller(player.getUUID(), player.getName().getString(), AccessLevel.OWNER);
    }

    @GameTest(maxTicks = 500)
    public void placeBlockGetsFreeMaterialForAuthorizedOwner(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 5, 2, 3);
        CompanionEntity companion = creativeCompanion(h, owner);
        BlockPos target = h.absolutePos(new BlockPos(8, 2, 3));
        PlaceBlockTask task = new PlaceBlockTask((BlockItem) Blocks.MUD_BRICKS.asItem(), target);
        companion.taskRunner().start(task, "place_block", caller(owner), true);
        h.succeedWhen(() -> {
            companion.taskRunner().tick();
            h.assertTrue(h.getLevel().getBlockState(target).is(Blocks.MUD_BRICKS),
                    "creative placement did not create the requested block");
            h.assertTrue(companion.inventory().countItem(Items.MUD_BRICKS) == 0,
                    "temporary placement material was inserted in the companion inventory");
            h.assertTrue(task.resultSummary().startsWith("placed "), "unexpected result: " + task.resultSummary());
        });
    }

    @GameTest(maxTicks = 700)
    public void buildGetsFreeBlueprintMaterials(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        CompanionEntity companion = creativeCompanion(h, owner);
        var blueprint = BuildTests.parse("""
                {"name":"creative_test","palette":{"C":"minecraft:cobblestone"},
                 "ops":[{"op":"box","from":[0,0,0],"to":[0,0,0],"block":"C"}]}
                """);
        BlockPos origin = h.absolutePos(new BlockPos(7, 2, 7));
        BuildTask task = new BuildTask(blueprint, origin, Rotation.NONE, true, false);
        companion.taskRunner().start(task, "build_structure", caller(owner), true);
        h.succeedWhen(() -> {
            companion.taskRunner().tick();
            h.assertTrue(h.getLevel().getBlockState(origin).is(Blocks.COBBLESTONE),
                    "blueprint did not place its free creative material");
            h.assertTrue(companion.inventory().countItem(Items.COBBLESTONE) == 0,
                    "creative build consumed or retained synthetic inventory material");
            h.assertTrue(task.resultSummary().contains("built"), "unexpected result: " + task.resultSummary());
        });
    }

    @GameTest
    public void placeBlockDoesNotGetFreeMaterialWithoutAnAuthorizedContext(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 5, 2, 3);
        CompanionEntity companion = creativeCompanion(h, owner);
        BlockPos target = h.absolutePos(new BlockPos(8, 2, 3));
        PlaceBlockTask task = new PlaceBlockTask((BlockItem) Blocks.MUD_BRICKS.asItem(), target);
        task.start(new dev.friendai.task.TaskContext(companion, h.getLevel()));
        TaskStatus status = task.tick(new dev.friendai.task.TaskContext(companion, h.getLevel()));
        h.assertTrue(status == TaskStatus.FAILED, "unauthorized task status was " + status);
        h.assertTrue(h.getLevel().getBlockState(target).isAir(), "unauthorized task placed a block");
        h.assertTrue(task.resultSummary().contains("I don't have"), "unexpected result: " + task.resultSummary());
        h.succeed();
    }

    @GameTest
    public void obtainCreatesMissingRegisteredItemForCreativeOwner(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = creativeCompanion(h, owner);
        ObtainTask task = new ObtainTask("minecraft:diamond", 130, false, false);
        companion.taskRunner().start(task, "obtain_item", caller(owner), true);
        h.succeedWhen(() -> {
            companion.taskRunner().tick();
            h.assertTrue(companion.inventory().countItem(Items.DIAMOND) == 130,
                    "obtain delivered " + companion.inventory().countItem(Items.DIAMOND));
            h.assertTrue(task.resultSummary().contains("got 130"), "unexpected result: " + task.resultSummary());
        });
    }
}
