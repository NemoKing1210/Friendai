package dev.friendai.test;

import com.google.gson.JsonParser;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.construction.special.Offset;
import dev.friendai.features.construction.special.SpecialStructure;
import dev.friendai.features.construction.special.StructureBook;
import dev.friendai.features.construction.special.StructureSnapshot;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.task.TaskListener;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.builtin.SpecialStructureTask;
import dev.friendai.task.structure.StructureIndex;
import dev.friendai.task.structure.StructureSite;
import dev.friendai.task.structure.StructureSnapshotBuilder;
import dev.friendai.tool.builtin.FriendaiTools;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;

public class StructureTests {
    private static SpecialStructure structure(String id) {
        return StructureBook.withBuiltins().get(id).orElseThrow();
    }

    private static StructureSnapshot snapshot(GameTestHelper h, CompanionEntity c, String id, int max) {
        StructureSite site = new StructureSite(h.absolutePos(new BlockPos(2, 2, 2)), Rotation.NONE);
        return StructureSnapshotBuilder.build(h.getLevel(), c, structure(id), site, max);
    }

    @GameTest
    public void book_matches_registries(GameTestHelper h) {
        var problems = StructureIndex.validate(StructureBook.withBuiltins());
        h.assertTrue(problems.isEmpty(), "registry problems: " + problems);
        h.succeed();
    }

    @GameTest
    public void snapshot_reports_free_site(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 10, 2, 10);
        StructureSnapshot snap = snapshot(h, c, "iron_golem", 4);
        h.assertTrue(snap.footprintFree(), "footprint should be free");
        h.assertFalse(snap.entityInFootprint(), "nobody stands there");
        h.assertFalse(snap.protectedArea(), "site is not protected");
        h.assertFalse(snap.inEnd(), "overworld");
        h.assertTrue(snap.correctBodyBlocks().isEmpty(), "nothing built yet");
        h.assertFalse(snap.outcomeAlreadyPresent(), "nothing finished yet");
        h.assertTrue(snap.nearbySpawned() == 0, "no golems yet, got " + snap.nearbySpawned());
        h.succeed();
    }

    @GameTest
    public void snapshot_sees_companion_in_footprint(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 3, 2, 2);
        StructureSnapshot snap = snapshot(h, c, "iron_golem", 4);
        h.assertTrue(snap.entityInFootprint(), "the companion stands in the footprint");
        h.succeed();
    }

    @GameTest
    public void snapshot_sees_player_in_footprint(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 10, 2, 10);
        GameTestSupport.mockPlayerAt(h, 3, 2, 2);
        StructureSnapshot snap = snapshot(h, c, "iron_golem", 4);
        h.assertTrue(snap.entityInFootprint(), "the player stands in the footprint");
        h.assertTrue(snap.blockers().contains("minecraft:player"), "blockers were " + snap.blockers());
        h.succeed();
    }

    @GameTest(maxTicks = 400)
    public void player_on_site_waits_instead_of_failing(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 10, 2, 10);
        c.inventory().addItem(new ItemStack(Items.IRON_BLOCK, 4));
        GameTestSupport.mockPlayerAt(h, 3, 2, 2);
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(task(h, "iron_golem", 2, 2, 2), "special:iron_golem");
        int[] ticks = {0};
        h.succeedWhen(() -> {
            runner.tick();
            ticks[0]++;
            if (ticks[0] < 205) {
                h.assertTrue(
                        result.status == null,
                        "must wait for the player, failed at tick " + ticks[0] + ": " + result.summary);
                return;
            }
            h.assertTrue(result.status == TaskStatus.FAILED, "status " + result.status + ": " + result.summary);
            h.assertTrue(result.summary.contains("minecraft:player"), "summary was " + result.summary);
        });
    }

    @GameTest
    public void snapshot_counts_nearby_golems(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 10, 2, 10);
        h.spawn(EntityTypes.IRON_GOLEM, 6, 2, 2);
        h.spawn(EntityTypes.IRON_GOLEM, 7, 2, 3);
        h.spawn(EntityTypes.SNOW_GOLEM, 6, 2, 3);
        StructureSnapshot snap = snapshot(h, c, "iron_golem", 4);
        h.assertTrue(snap.nearbySpawned() == 2, "expected 2 iron golems, got " + snap.nearbySpawned());
        h.succeed();
    }

    @GameTest
    public void snapshot_marks_matching_body_blocks(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 10, 2, 10);
        h.setBlock(3, 2, 2, net.minecraft.world.level.block.Blocks.IRON_BLOCK);
        StructureSnapshot snap = snapshot(h, c, "iron_golem", 4);
        h.assertTrue(
                snap.correctBodyBlocks().contains(new dev.friendai.features.construction.special.Offset(1, 0, 0)),
                "the iron block at offset 1,0,0 should count as built");
        h.assertTrue(snap.footprintFree(), "a correct body block does not block the footprint");
        h.succeed();
    }

    private static final class Result implements TaskListener {
        TaskStatus status;
        String summary = "";

        @Override
        public void onFinished(int id, String toolName, TaskStatus status, String summary) {
            this.status = status;
            this.summary = summary;
        }

        @Override
        public void onCancelled(int id, String toolName, String reason) {}

        @Override
        public void onNotice(String text) {}
    }

    private static ExecutionCoordinator runner(CompanionEntity companion, Result result) {
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 600, () -> false);
        runner.setListener(result);
        return runner;
    }

    private static SpecialStructureTask task(GameTestHelper h, String id, int x, int y, int z) {
        return new SpecialStructureTask(structure(id), h.absolutePos(new BlockPos(x, y, z)), Rotation.NONE, 4);
    }

    private static int count(GameTestHelper h, net.minecraft.world.entity.EntityType<?> type) {
        return h.getLevel()
                .getEntities(type, h.getBounds().inflate(4), e -> true)
                .size();
    }

    private static ToolResult callTool(
            GameTestHelper h, CompanionEntity c, AccessLevel access, String name, String json) {
        ToolCall call = new ToolCall("1", name, JsonParser.parseString(json).getAsJsonObject(), json, null);
        return FriendaiTools.create(() -> 32).bind(c).execute(call, new Caller(UUID.randomUUID(), "tester", access));
    }

    private static String siteArgs(GameTestHelper h, String kind, int x, int y, int z) {
        BlockPos pos = h.absolutePos(new BlockPos(x, y, z));
        return "{\"kind\":\"" + kind + "\",\"x\":" + pos.getX() + ",\"y\":" + pos.getY() + ",\"z\":" + pos.getZ()
                + ",\"facing\":\"south\"}";
    }

    @GameTest(maxTicks = 1200)
    public void tool_build_special_builds_snow_golem(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 8, 2, 8);
        c.inventory().addItem(new ItemStack(Items.SNOW_BLOCK, 2));
        c.inventory().addItem(new ItemStack(Items.CARVED_PUMPKIN, 1));
        ToolResult r = callTool(h, c, AccessLevel.TRUSTED, "build_special", siteArgs(h, "snow_golem", 3, 2, 3));
        h.assertFalse(r.error(), "error: " + r.content());
        h.succeedWhen(() -> h.assertTrue(count(h, EntityTypes.SNOW_GOLEM) >= 1, "a snow golem should exist"));
    }

    @GameTest
    public void tool_build_special_rejects_unknown_kind(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 8, 2, 8);
        ToolResult r = callTool(h, c, AccessLevel.TRUSTED, "build_special", siteArgs(h, "dragon", 3, 2, 3));
        h.assertTrue(r.error(), "an unknown kind must be an error");
        h.assertTrue(r.content().contains("iron_golem"), "the error should list valid kinds: " + r.content());
        h.succeed();
    }

    @GameTest
    public void tool_plan_special_reports_missing_item(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 8, 2, 8);
        ToolResult r = callTool(h, c, AccessLevel.TRUSTED, "plan_special", siteArgs(h, "nether_portal", 3, 2, 3));
        h.assertFalse(r.error(), "error: " + r.content());
        h.assertTrue(r.content().contains("minecraft:flint_and_steel"), "result was " + r.content());
        h.assertTrue(
                c.executionCoordinator().current().isEmpty()
                        || !c.executionCoordinator().current().get().type().startsWith("special:"),
                "planning must not start a task");
        h.succeed();
    }

    @GameTest
    public void tool_build_special_needs_the_finishing_block(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 8, 2, 8);
        c.inventory().addItem(new ItemStack(Items.SNOW_BLOCK, 2));
        ToolResult r = callTool(h, c, AccessLevel.TRUSTED, "build_special", siteArgs(h, "snow_golem", 3, 2, 3));
        h.assertTrue(r.error(), "no pumpkin must be refused up front");
        h.assertTrue(r.content().contains("minecraft:carved_pumpkin"), "result was " + r.content());
        h.succeed();
    }

    @GameTest
    public void tool_build_special_guest_denied(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 8, 2, 8);
        ToolResult r = callTool(h, c, AccessLevel.GUEST, "build_special", siteArgs(h, "snow_golem", 3, 2, 3));
        h.assertTrue(r.error(), "a guest must not build");
        h.succeed();
    }

    @GameTest(maxTicks = 1200)
    public void snow_golem_spawns(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 8, 2, 8);
        c.inventory().addItem(new ItemStack(Items.SNOW_BLOCK, 2));
        c.inventory().addItem(new ItemStack(Items.CARVED_PUMPKIN, 1));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(task(h, "snow_golem", 3, 2, 3), "build_special");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(count(h, EntityTypes.SNOW_GOLEM) >= 1, "a snow golem should exist");
        });
    }

    @GameTest(maxTicks = 1600)
    public void iron_golem_spawns(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 10, 2, 10);
        c.inventory().addItem(new ItemStack(Items.IRON_BLOCK, 4));
        c.inventory().addItem(new ItemStack(Items.CARVED_PUMPKIN, 1));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(task(h, "iron_golem", 3, 2, 3), "build_special");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(count(h, EntityTypes.IRON_GOLEM) >= 1, "an iron golem should exist");
        });
    }

    @GameTest(maxTicks = 4000)
    public void nether_portal_lights(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 10, 2, 10);
        c.inventory().addItem(new ItemStack(Items.OBSIDIAN, 10));
        c.inventory().addItem(new ItemStack(Items.COBBLESTONE, 16));
        c.inventory().addItem(new ItemStack(Items.FLINT_AND_STEEL, 1));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(task(h, "nether_portal", 3, 2, 3), "build_special");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(h.getBlockState(new BlockPos(4, 3, 3)).is(Blocks.NETHER_PORTAL), "the portal should be lit");
        });
    }

    @GameTest(maxTicks = 400)
    public void blocked_by_stone(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 10, 2, 10);
        c.inventory().addItem(new ItemStack(Items.SNOW_BLOCK, 2));
        c.inventory().addItem(new ItemStack(Items.CARVED_PUMPKIN, 1));
        h.setBlock(3, 3, 3, Blocks.STONE);
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(task(h, "snow_golem", 3, 2, 3), "build_special");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "status " + result.status);
            h.assertTrue(result.summary.contains("room"), "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 400)
    public void protected_area_fails_with_reason(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 10, 2, 10);
        c.inventory().addItem(new ItemStack(Items.SNOW_BLOCK, 2));
        c.inventory().addItem(new ItemStack(Items.CARVED_PUMPKIN, 1));
        c.hands().overrideProtection(pos -> true);
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(task(h, "snow_golem", 3, 2, 3), "build_special");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "status " + result.status);
            h.assertTrue(result.summary.contains("protect"), "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 400)
    public void finishing_item_missing_names_item(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 10, 2, 10);
        c.inventory().addItem(new ItemStack(Items.OBSIDIAN, 10));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(task(h, "nether_portal", 3, 2, 3), "build_special");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "status " + result.status);
            h.assertTrue(result.summary.contains("flint_and_steel"), "summary was " + result.summary);
            h.assertTrue(h.getBlockState(new BlockPos(3, 3, 3)).isAir(), "nothing should be built without the item");
        });
    }

    @GameTest(maxTicks = 1200)
    public void existing_frame_skips_building(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 10, 2, 10);
        c.inventory().addItem(new ItemStack(Items.FLINT_AND_STEEL, 1));
        StructureSite site = new StructureSite(h.absolutePos(new BlockPos(3, 2, 3)), Rotation.NONE);
        SpecialStructure portal = structure("nether_portal");
        for (var block : portal.body().blocks()) {
            h.getLevel()
                    .setBlock(
                            site.pos(portal, new Offset(block.x(), block.y(), block.z())),
                            Blocks.OBSIDIAN.defaultBlockState(),
                            3);
        }
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(task(h, "nether_portal", 3, 2, 3), "build_special");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(h.getBlockState(new BlockPos(4, 3, 3)).is(Blocks.NETHER_PORTAL), "the portal should be lit");
        });
    }
}
