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
import net.minecraft.world.phys.AABB;

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

    @GameTest(maxTicks = 1800)
    public void houseBuildUsesUnlimitedCreativeMaterialsBeyondStackSize(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 7, 2, 7);
        CompanionEntity companion = creativeCompanion(h, owner);
        var blueprint = BuildTests.parse("""
                {"name":"creative_house","palette":{"P":"minecraft:oak_planks"},"ops":[
                  {"op":"box","from":[0,0,0],"to":[4,0,4],"block":"P"},
                  {"op":"box","from":[0,1,0],"to":[4,5,0],"block":"P"},
                  {"op":"box","from":[0,1,4],"to":[4,5,4],"block":"P"},
                  {"op":"box","from":[0,1,1],"to":[0,5,3],"block":"P"},
                  {"op":"box","from":[4,1,1],"to":[4,5,3],"block":"P"},
                  {"op":"box","from":[0,5,0],"to":[4,5,4],"block":"P"}
                ]}
                """);
        h.assertTrue(blueprint.blocks().size() > 64,
                "the house should require more than one stack; blocks=" + blueprint.blocks().size());
        BlockPos origin = h.absolutePos(new BlockPos(1, 2, 1));
        BuildTask task = new BuildTask(blueprint, origin, Rotation.NONE, true, false);
        final TaskStatus[] status = {null};
        final String[] summary = {""};
        companion.taskRunner().setListener(new dev.friendai.task.TaskListener() {
            @Override public void onFinished(int id, String toolName, TaskStatus taskStatus, String result) {
                status[0] = taskStatus;
                summary[0] = result;
            }
            @Override public void onCancelled(int id, String toolName, String reason) {}
        });
        companion.taskRunner().start(task, "build_structure", caller(owner), true);

        h.succeedWhen(() -> {
            for (int x = 0; x < 9; x++) {
                for (int y = 2; y <= 11; y++) {
                    for (int z = 0; z < 9; z++) {
                        var state = h.getBlockState(new BlockPos(x, y, z));
                        h.assertFalse(state.is(Blocks.DIRT) || state.is(Blocks.COBBLESTONE),
                                "creative building placed a scaffold block at " + x + " " + y + " " + z);
                    }
                }
            }
            h.assertTrue(status[0] == TaskStatus.SUCCESS,
                    "creative house did not finish; status=" + status[0] + ", result=" + summary[0]);
            h.assertTrue(summary[0].startsWith("built creative_house"), "unexpected result: " + summary[0]);
            h.assertTrue(companion.inventory().countItem(Items.OAK_PLANKS) == 0,
                    "creative materials must not be inserted into inventory");
            h.assertTrue(h.getBlockState(new BlockPos(3, 2, 3)).is(Blocks.OAK_PLANKS), "house floor was not built");
            h.assertTrue(h.getBlockState(new BlockPos(3, 7, 1)).is(Blocks.OAK_PLANKS), "upper wall was not built");
            h.assertTrue(h.getBlockState(new BlockPos(3, 7, 3)).is(Blocks.OAK_PLANKS), "roof was not built");
        });
    }

    @GameTest(maxTicks = 500)
    public void creativeBuildCanStepVerticallyOutOfOneBlockShaft(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 13, 2, 13);
        CompanionEntity companion = creativeCompanion(h, owner);
        int x = 7;
        int z = 7;
        for (int y = 2; y <= 7; y++) {
            for (int wallX = 4; wallX <= 10; wallX++) {
                for (int wallZ = 4; wallZ <= 10; wallZ++) {
                    if (wallX == x && wallZ == z) continue;
                    h.setBlock(wallX, y, wallZ, Blocks.STONE);
                }
            }
        }
        BlockPos target = h.absolutePos(new BlockPos(x, 2, z));
        companion.snapTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0, 0);
        var blueprint = BuildTests.parse("""
                {"name":"shaft_exit","palette":{"C":"minecraft:cobblestone"},
                 "ops":[{"op":"box","from":[0,0,0],"to":[0,0,0],"block":"C"}]}
                """);
        BuildTask task = new BuildTask(blueprint, target, Rotation.NONE, true, false);
        final TaskStatus[] status = {null};
        final String[] summary = {""};
        companion.taskRunner().setListener(new dev.friendai.task.TaskListener() {
            @Override public void onFinished(int id, String toolName, TaskStatus taskStatus, String result) {
                status[0] = taskStatus;
                summary[0] = result;
            }
            @Override public void onCancelled(int id, String toolName, String reason) {}
        });
        companion.taskRunner().start(task, "build_structure", caller(owner), true);
        final double[] highestY = {companion.getY()};

        h.succeedWhen(() -> {
            companion.taskRunner().tick();
            highestY[0] = Math.max(highestY[0], companion.getY());
            h.assertTrue(status[0] != null, "creative shaft build did not finish");
            h.assertTrue(status[0] == TaskStatus.SUCCESS,
                    "creative shaft build failed; result=" + summary[0]);
            h.assertTrue(summary[0].startsWith("built shaft_exit"), "unexpected result: " + summary[0]);
            h.assertTrue(highestY[0] >= target.getY() + 1.0,
                    "companion did not use the vertical opening to leave the placement cell; highest Y="
                            + highestY[0]);
            h.assertFalse(companion.getBoundingBox().intersects(new AABB(target)),
                    "companion still overlaps the block it built in the shaft");
            h.assertTrue(h.getLevel().getBlockState(target).is(Blocks.COBBLESTONE),
                    "shaft block was not placed; task=" + task.resultSummary() + ", status=" + status[0]);
            h.assertFalse(summary[0].contains("scaffold blocks"),
                    "creative shaft build should not use scaffolding: " + summary[0]);
        });
    }

    @GameTest(maxTicks = 800)
    public void tallCreativeBuildFliesWithoutPillaring(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 7, 2, 7);
        CompanionEntity companion = creativeCompanion(h, owner);
        for (int x = 3; x <= 5; x++) {
            for (int y = 7; y <= 14; y++) {
                for (int z = 3; z <= 5; z++) h.setBlock(x, y, z, Blocks.AIR);
            }
        }
        var blueprint = BuildTests.parse("""
                {"name":"creative_tower","palette":{"C":"minecraft:cobblestone"},
                 "ops":[{"op":"box","from":[0,0,0],"to":[0,8,0],"block":"C"}]}
                """);
        BlockPos origin = h.absolutePos(new BlockPos(4, 2, 4));
        BuildTask task = new BuildTask(blueprint, origin, Rotation.NONE, true, false);
        final TaskStatus[] status = {null};
        final String[] summary = {""};
        companion.taskRunner().setListener(new dev.friendai.task.TaskListener() {
            @Override public void onFinished(int id, String toolName, TaskStatus taskStatus, String result) {
                status[0] = taskStatus;
                summary[0] = result;
            }
            @Override public void onCancelled(int id, String toolName, String reason) {}
        });
        companion.taskRunner().start(task, "build_structure", caller(owner), true);

        h.succeedWhen(() -> {
            for (int x = 0; x < 9; x++) {
                for (int y = 2; y <= 10; y++) {
                    for (int z = 0; z < 9; z++) {
                        h.assertFalse(h.getBlockState(new BlockPos(x, y, z)).is(Blocks.DIRT),
                                "creative tower used a dirt access pillar at " + x + " " + y + " " + z);
                    }
                }
            }
            h.assertTrue(status[0] == TaskStatus.SUCCESS,
                    "creative tower did not finish; status=" + status[0] + ", result=" + summary[0]);
            h.assertTrue(summary[0].startsWith("built creative_tower"), "unexpected result: " + summary[0]);
            h.assertFalse(summary[0].contains("scaffold blocks"),
                    "creative tower should not need construction scaffolding: " + summary[0]);
            for (int y = 2; y <= 10; y++) {
                h.assertTrue(h.getBlockState(new BlockPos(4, y, 4)).is(Blocks.COBBLESTONE),
                        "tower block missing at y=" + y);
            }
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
