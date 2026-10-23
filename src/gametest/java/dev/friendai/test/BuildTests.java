package dev.friendai.test;

import com.google.gson.JsonObject;
import dev.friendai.build.Blueprint;
import dev.friendai.build.BlueprintParser;
import dev.friendai.build.ParseResult;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.task.TaskListener;
import dev.friendai.task.TaskRunner;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.build.BlueprintResolver;
import dev.friendai.task.build.BlueprintStore;
import dev.friendai.task.build.Materials;
import dev.friendai.task.build.Parts;
import dev.friendai.task.build.Placement;
import dev.friendai.task.build.Site;
import dev.friendai.task.builtin.BuildTask;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

public class BuildTests {
    private static final Block RED_BED = Blocks.BED.pick(DyeColor.RED);
    private static final String EXAMPLE = """
            {
              "name": "hut",
              "palette": { "C": "minecraft:cobblestone" },
              "ops": [ { "op": "box", "from": [0,0,0], "to": [2,0,2], "block": "C" } ]
            }
            """;

    static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    static void equal(Object expected, Object actual, String what) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }

    static BlockState state(String text) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, text, false).blockState();
        } catch (Exception e) {
            throw new IllegalArgumentException("bad test state " + text, e);
        }
    }

    static Blueprint parse(String json) {
        ParseResult result = BlueprintParser.parse(json, BlueprintResolver.knownState());
        check(result.blueprint().isPresent(), "could not parse: " + result.errors());
        return result.blueprint().get();
    }

    private static JsonObject args(String key, String value) {
        JsonObject args = new JsonObject();
        args.addProperty(key, value);
        return args;
    }

    @GameTest
    public void exactlyOneSource(GameTestHelper h) {
        ParseResult none = BlueprintResolver.resolve(new JsonObject(), h.getLevel(), null);
        equal(List.of("give exactly one of blueprint, generator, saved, structure"), none.errors(), "no source");

        JsonObject two = args("blueprint", EXAMPLE);
        two.addProperty("generator", "{\"type\":\"platform\",\"width\":3,\"depth\":3,\"block\":\"minecraft:stone\"}");
        ParseResult both = BlueprintResolver.resolve(two, h.getLevel(), null);
        equal(List.of("give exactly one of blueprint, generator, saved, structure"), both.errors(), "two sources");
        h.succeed();
    }

    @GameTest
    public void resolvesGenerator(GameTestHelper h) {
        ParseResult result = BlueprintResolver.resolve(
                args("generator", "{\"type\":\"wall\",\"length\":5,\"height\":3,\"block\":\"minecraft:cobblestone\"}"),
                h.getLevel(),
                null);
        check(result.errors().isEmpty(), "unexpected errors: " + result.errors());
        Blueprint wall = result.blueprint().orElseThrow();
        equal("wall", wall.name(), "name");
        equal(5, wall.width(), "width");
        equal(3, wall.height(), "height");
        h.succeed();
    }

    @GameTest
    public void resolvesBlueprint(GameTestHelper h) {
        ParseResult result = BlueprintResolver.resolve(args("blueprint", EXAMPLE), h.getLevel(), null);
        check(result.errors().isEmpty(), "unexpected errors: " + result.errors());
        equal(3, result.blueprint().orElseThrow().width(), "width");
        h.succeed();
    }

    @GameTest
    public void rejectsUnknownState(GameTestHelper h) {
        ParseResult result = BlueprintResolver.resolve(args("blueprint", """
                {
                  "palette": { "C": "minecraft:not_a_block" },
                  "ops": [ { "op": "box", "from": [0,0,0], "to": [1,0,1], "block": "C" } ]
                }
                """), h.getLevel(), null);
        equal(List.of("unknown block minecraft:not_a_block"), result.errors(), "errors");
        h.succeed();
    }

    @GameTest
    public void structureNotSupportedYet(GameTestHelper h) {
        ParseResult result = BlueprintResolver.resolve(args("structure", "minecraft:village_plains"), h.getLevel(), null);
        equal(List.of("structure templates are not supported yet"), result.errors(), "errors");
        h.succeed();
    }

    @GameTest
    public void placementRotatesPositionsAndStates(GameTestHelper h) {
        Blueprint blueprint = parse("""
                {
                  "palette": {
                    "L": "minecraft:oak_log[axis=x]",
                    "S": "minecraft:oak_stairs[facing=north,half=bottom]",
                    "B": "minecraft:oak_slab[type=top]"
                  },
                  "ops": [
                    { "op": "box", "from": [0,0,0], "to": [0,0,0], "block": "L" },
                    { "op": "box", "from": [1,0,0], "to": [1,0,0], "block": "S" },
                    { "op": "box", "from": [0,0,1], "to": [0,0,1], "block": "B" }
                  ]
                }
                """);
        BlockPos origin = h.absolutePos(new BlockPos(2, 2, 2));
        Placement.PlacedBlueprint placed = Placement.of(blueprint, origin, Direction.WEST, null);
        equal(Rotation.CLOCKWISE_90, placed.rotation(), "rotation");
        equal(3, placed.specs().size(), "spec count");

        equal(state("minecraft:oak_log[axis=x]").rotate(Rotation.CLOCKWISE_90),
                stateAt(placed, origin.offset(1, 0, 0)), "log state at its rotated position");
        equal(state("minecraft:oak_stairs[facing=north,half=bottom]").rotate(Rotation.CLOCKWISE_90),
                stateAt(placed, origin.offset(1, 0, 1)), "stairs state at its rotated position");
        equal(state("minecraft:oak_slab[type=top]").rotate(Rotation.CLOCKWISE_90),
                stateAt(placed, origin.offset(0, 0, 0)), "slab state at its rotated position");
        h.succeed();
    }

    private static BlockState stateAt(Placement.PlacedBlueprint placed, BlockPos pos) {
        return placed.specs().stream()
                .filter(spec -> spec.pos().equals(pos))
                .map(Placement.PlacedSpec::wanted)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no spec at " + pos));
    }

    @GameTest
    public void defaultOriginInFrontOfOwner(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        var owner = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        Blueprint blueprint = parse("""
                {
                  "palette": { "C": "minecraft:cobblestone" },
                  "ops": [ { "op": "box", "from": [0,0,0], "to": [0,0,0], "block": "C" } ]
                }
                """);
        Placement.PlacedBlueprint placed = Placement.of(blueprint, null, null, companion);
        BlockPos ownerPos = owner.blockPosition();
        equal(ownerPos.getX(), placed.origin().getX(), "origin x");
        equal(ownerPos.getZ() + 3, placed.origin().getZ(), "origin z, three blocks in front");
        equal(Rotation.CLOCKWISE_180, placed.rotation(), "the front must face the owner");
        equal(h.getLevel().getHeight(Heightmap.Types.WORLD_SURFACE, ownerPos.getX(), ownerPos.getZ() + 3),
                placed.origin().getY(), "origin y on the ground");
        h.succeed();
    }

    @GameTest
    public void storeSaveLoadList(GameTestHelper h) throws Exception {
        Path dir = Files.createTempDirectory("friendai-blueprints");
        BlueprintStore store = new BlueprintStore(dir);
        Blueprint blueprint = parse(EXAMPLE);
        UUID author = UUID.randomUUID();
        store.save("hut", blueprint, author, false);

        equal(Optional.of(blueprint), store.load("hut"), "loaded blueprint");
        List<BlueprintStore.Entry> entries = store.list();
        equal(1, entries.size(), "entry count");
        equal("hut", entries.get(0).name(), "name");
        equal(3, entries.get(0).width(), "width");
        equal(1, entries.get(0).height(), "height");
        equal(3, entries.get(0).depth(), "depth");
        equal(Optional.of(author), entries.get(0).author(), "author");
        h.succeed();
    }

    @GameTest
    public void storeRejectsBadName(GameTestHelper h) throws Exception {
        Path dir = Files.createTempDirectory("friendai-blueprints");
        BlueprintStore store = new BlueprintStore(dir);
        Blueprint blueprint = parse(EXAMPLE);
        try {
            store.save("Bad Name!", blueprint, null, false);
            throw new AssertionError("bad name must be refused");
        } catch (IllegalArgumentException e) {
            equal("blueprint names use a-z, 0-9, _ and -, at most 32 characters", e.getMessage(), "message");
        }
        check(store.load("Bad Name!").isEmpty(), "a bad name must not load");
        h.succeed();
    }

    @GameTest
    public void storeNoOverwriteWithoutFlag(GameTestHelper h) throws Exception {
        Path dir = Files.createTempDirectory("friendai-blueprints");
        BlueprintStore store = new BlueprintStore(dir);
        Blueprint blueprint = parse(EXAMPLE);
        store.save("hut", blueprint, null, false);
        try {
            store.save("hut", blueprint, null, false);
            throw new AssertionError("an existing blueprint must be refused");
        } catch (IllegalArgumentException e) {
            equal("a blueprint named hut exists; use overwrite", e.getMessage(), "message");
        }
        store.save("hut", blueprint, null, true);
        equal(1, store.list().size(), "one blueprint after overwrite");
        h.succeed();
    }

    @GameTest
    public void chestBlocksSite(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(5, 2, 5, Blocks.CHEST);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Site.SiteReport report = site(h, companion, 5, 2, 5, "minecraft:cobblestone", true);
        String at = coords(h.absolutePos(new BlockPos(5, 2, 5)));
        equal(List.of("the site isn't clear: minecraft:chest at " + at), report.problems(), "problems");
        h.succeed();
    }

    @GameTest
    public void naturalBlocksCleared(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(5, 2, 5, Blocks.SHORT_GRASS);
        h.setBlock(6, 2, 6, Blocks.TALL_GRASS);
        h.setBlock(7, 2, 7, Blocks.DIRT);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Blueprint blueprint = parse("""
                {
                  "palette": { "C": "minecraft:cobblestone" },
                  "ops": [ { "op": "box", "from": [0,0,0], "to": [2,0,2], "block": "C" } ]
                }
                """);
        Site.SiteReport report = Site.check(
                h.getLevel(),
                Placement.of(blueprint, h.absolutePos(new BlockPos(5, 2, 5)), Direction.SOUTH, companion),
                companion,
                true);
        check(report.problems().isEmpty(), "unexpected problems: " + report.problems());
        equal(List.of(
                        h.absolutePos(new BlockPos(5, 2, 5)),
                        h.absolutePos(new BlockPos(6, 2, 6)),
                        h.absolutePos(new BlockPos(7, 2, 7))),
                report.toClear(), "positions to clear");
        h.succeed();
    }

    @GameTest
    public void clearSiteOff(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(5, 2, 5, Blocks.DIRT);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Site.SiteReport report = site(h, companion, 5, 2, 5, "minecraft:cobblestone", false);
        String at = coords(h.absolutePos(new BlockPos(5, 2, 5)));
        equal(List.of("the site isn't clear: minecraft:dirt at " + at), report.problems(), "problems");
        check(report.toClear().isEmpty(), "nothing may be cleared with clear_site off");
        h.succeed();
    }

    @GameTest
    public void foundationFillsHole(GameTestHelper h) {
        GameTestSupport.platformAt(h, 3, 16);
        h.setBlock(5, 3, 5, Blocks.AIR);
        h.setBlock(5, 2, 5, Blocks.AIR);
        h.setBlock(5, 1, 5, Blocks.STONE);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 4, 2);
        companion.inventory().addItem(new ItemStack(Items.DIRT, 10));
        Site.SiteReport report = site(h, companion, 5, 4, 5, "minecraft:cobblestone", true);
        check(report.problems().isEmpty(), "unexpected problems: " + report.problems());
        equal(2, report.foundation().size(), "two foundation blocks");
        equal(h.absolutePos(new BlockPos(5, 3, 5)), report.foundation().get(0).pos(), "first foundation block");
        equal(h.absolutePos(new BlockPos(5, 2, 5)), report.foundation().get(1).pos(), "second foundation block");
        equal(state("minecraft:dirt"), report.foundation().get(0).wanted(), "foundation material from the inventory");
        h.succeed();
    }

    @GameTest
    public void foundationTooDeep(GameTestHelper h) {
        GameTestSupport.platformAt(h, 6, 16);
        for (int y = 2; y <= 6; y++) {
            h.setBlock(5, y, 5, Blocks.AIR);
        }
        h.setBlock(5, 1, 5, Blocks.STONE);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 7, 2);
        Site.SiteReport report = site(h, companion, 5, 7, 5, "minecraft:cobblestone", true);
        String at = coords(h.absolutePos(new BlockPos(5, 7, 5)));
        equal(List.of("the ground under " + at + " drops more than 4 blocks"), report.problems(), "problems");
        equal(4, report.foundation().size(), "foundation stops at four blocks");
        h.succeed();
    }

    @GameTest
    public void foundationOnlyUnderBlocks(GameTestHelper h) {
        GameTestSupport.platformAt(h, 3, 16);
        for (int y = 2; y <= 3; y++) {
            h.setBlock(4, y, 4, Blocks.AIR);
            h.setBlock(5, y, 5, Blocks.AIR);
        }
        h.setBlock(4, 1, 4, Blocks.STONE);
        h.setBlock(5, 1, 5, Blocks.STONE);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 4, 2);
        Blueprint blueprint = parse("""
                {
                  "palette": { "C": "minecraft:cobblestone", "_": "minecraft:air" },
                  "ops": [
                    { "op": "box", "from": [0,0,0], "to": [0,0,0], "block": "C" },
                    { "op": "box", "from": [1,0,1], "to": [1,0,1], "block": "_" }
                  ]
                }
                """);
        Site.SiteReport report = Site.check(
                h.getLevel(),
                Placement.of(blueprint, h.absolutePos(new BlockPos(4, 4, 4)), Direction.SOUTH, companion),
                companion,
                true);
        equal(2, report.foundation().size(), "foundation only under the cobblestone spec");
        int column = h.absolutePos(new BlockPos(4, 0, 0)).getX();
        for (Placement.PlacedSpec spec : report.foundation()) {
            equal(column, spec.pos().getX(), "no foundation under the air spec");
        }
        h.succeed();
    }

    @GameTest
    public void protectedPosition(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos target = h.absolutePos(new BlockPos(5, 2, 5));
        companion.hands().overrideProtection(pos -> pos.equals(target));
        Site.SiteReport report = site(h, companion, 5, 2, 5, "minecraft:cobblestone", true);
        equal(List.of(coords(target) + " is protected"), report.problems(), "problems");
        h.succeed();
    }

    @GameTest
    public void doorCountsOneItem(GameTestHelper h) {
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        List<Placement.PlacedSpec> specs = List.of(
                new Placement.PlacedSpec(pos, state("minecraft:oak_door[facing=south,half=lower]")),
                new Placement.PlacedSpec(pos.above(), state("minecraft:oak_door[facing=south,half=upper]")));
        java.util.Map<net.minecraft.world.item.Item, Integer> needed = Materials.needed(specs, List.of());
        equal(java.util.Map.of(Items.OAK_DOOR, 1), needed, "one door");
        h.succeed();
    }

    @GameTest
    public void wallTorchNeedsTorch(GameTestHelper h) {
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        List<Placement.PlacedSpec> specs =
                List.of(new Placement.PlacedSpec(pos, state("minecraft:wall_torch[facing=south]")));
        equal(java.util.Map.of(Items.TORCH, 1), Materials.needed(specs, List.of()), "a wall torch is one torch");
        h.succeed();
    }

    @GameTest
    public void haveCountsChests(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.OAK_PLANKS, 10));
        h.setBlock(6, 2, 6, Blocks.CHEST);
        var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity)
                h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(6, 2, 6)));
        chest.setItem(0, new ItemStack(Items.OAK_PLANKS, 20));
        chest.setChanged();
        equal(30, Materials.have(companion, h.getLevel()).getOrDefault(Items.OAK_PLANKS, 0), "inventory plus chest");
        h.succeed();
    }

    private static final class Result implements TaskListener {
        TaskStatus status;
        String summary;
        final java.util.List<String> notices = new java.util.ArrayList<>();

        @Override
        public void onFinished(int id, String toolName, TaskStatus status, String summary) {
            this.status = status;
            this.summary = summary;
        }

        @Override
        public void onCancelled(int id, String toolName, String reason) {}

        @Override
        public void onNotice(String text) {
            notices.add(text);
        }
    }

    private static TaskRunner runner(CompanionEntity companion, Result result) {
        TaskRunner runner = new TaskRunner(companion, () -> 300, () -> false);
        runner.setListener(result);
        return runner;
    }

    private static BuildTask build(Blueprint blueprint, GameTestHelper h, int x, int y, int z, Direction facing) {
        BlockPos origin = h.absolutePos(new BlockPos(x, y, z));
        return new BuildTask(blueprint, origin, Placement.rotationFor(facing), true);
    }

    private static Blueprint generator(String json) {
        ParseResult result = dev.friendai.build.Generators.generate(
                com.google.gson.JsonParser.parseString(json).getAsJsonObject(), BlueprintResolver.knownState());
        check(result.blueprint().isPresent(), "generator failed: " + result.errors());
        return result.blueprint().get();
    }

    @GameTest(maxTicks = 2000)
    public void buildsGeneratedWall(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.COBBLESTONE, 15));
        Blueprint wall = generator(
                "{\"type\":\"wall\",\"length\":5,\"height\":3,\"block\":\"minecraft:cobblestone\"}");
        Result result = new Result();
        TaskRunner runner = runner(companion, result);
        runner.start(build(wall, h, 4, 2, 6, Direction.SOUTH), "build_structure");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(result.summary.startsWith("built wall at "), "summary was " + result.summary);
            for (int x = 4; x <= 8; x++) {
                for (int y = 2; y <= 4; y++) {
                    h.assertTrue(h.getBlockState(new BlockPos(x, y, 6)).is(Blocks.COBBLESTONE),
                            "missing wall block at " + x + "," + y + ",6: " + h.getBlockState(new BlockPos(x, y, 6)));
                }
            }
            h.assertTrue(companion.inventory().countItem(Items.COBBLESTONE) == 0, "all cobblestone must be used");
        });
    }

    @GameTest(maxTicks = 8000)
    public void buildsSmallHouseFromChest(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.setBlock(2, 2, 9, Blocks.CHEST);
        var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity)
                h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(2, 2, 9)));
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        chest.setItem(1, new ItemStack(Items.OAK_PLANKS, 64));
        chest.setItem(2, new ItemStack(Items.OAK_PLANKS, 64));
        chest.setItem(3, new ItemStack(Items.GLASS_PANE, 16));
        chest.setItem(4, new ItemStack(Items.OAK_SLAB, 32));
        chest.setItem(5, new ItemStack(Items.OAK_DOOR, 2));
        chest.setChanged();
        Blueprint house = generator("""
                { "type": "house", "width": 5, "depth": 5, "roof_style": "flat",
                  "walls": "minecraft:oak_planks", "floor": "minecraft:cobblestone", "roof": "minecraft:oak_slab" }
                """);
        Result result = new Result();
        TaskRunner runner = runner(companion, result);
        runner.start(build(house, h, 4, 2, 4, Direction.SOUTH), "build_structure");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(result.summary.startsWith("built house at "), "summary was " + result.summary);
            h.assertTrue(h.getBlockState(new BlockPos(4, 2, 4)).is(Blocks.COBBLESTONE), "floor");
            h.assertTrue(h.getBlockState(new BlockPos(4, 3, 4)).is(Blocks.OAK_PLANKS), "wall");
            h.assertTrue(chest.getItem(0).getCount() < 64, "materials must come from the chest");
        });
    }

    @GameTest(maxTicks = 2000)
    public void rotationKeepsAxisAndFacing(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.OAK_LOG));
        companion.inventory().addItem(new ItemStack(Items.OAK_STAIRS));
        companion.inventory().addItem(new ItemStack(Items.OAK_SLAB));
        Blueprint blueprint = parse("""
                {
                  "palette": {
                    "L": "minecraft:oak_log[axis=x]",
                    "S": "minecraft:oak_stairs[facing=north,half=bottom]",
                    "B": "minecraft:oak_slab[type=top]"
                  },
                  "ops": [
                    { "op": "box", "from": [0,0,0], "to": [0,0,0], "block": "L" },
                    { "op": "box", "from": [1,0,0], "to": [1,0,0], "block": "S" },
                    { "op": "box", "from": [0,0,1], "to": [0,0,1], "block": "B" }
                  ]
                }
                """);
        Result result = new Result();
        TaskRunner runner = runner(companion, result);
        runner.start(build(blueprint, h, 6, 2, 6, Direction.WEST), "build_structure");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            Rotation rotation = Rotation.CLOCKWISE_90;
            h.assertTrue(h.getBlockState(new BlockPos(7, 2, 6)).equals(
                            state("minecraft:oak_log[axis=x]").rotate(rotation)),
                    "log at 7,2,6 was " + h.getBlockState(new BlockPos(7, 2, 6)));
            h.assertTrue(h.getBlockState(new BlockPos(7, 2, 7)).equals(
                            state("minecraft:oak_stairs[facing=north,half=bottom]").rotate(rotation)),
                    "stairs at 7,2,7 were " + h.getBlockState(new BlockPos(7, 2, 7)));
            h.assertTrue(h.getBlockState(new BlockPos(6, 2, 6)).equals(
                            state("minecraft:oak_slab[type=top]").rotate(rotation)),
                    "slab at 6,2,6 was " + h.getBlockState(new BlockPos(6, 2, 6)));
        });
    }

    @GameTest(maxTicks = 200)
    public void missingMaterialsFails(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.COBBLESTONE, 5));
        Blueprint wall = generator(
                "{\"type\":\"wall\",\"length\":5,\"height\":3,\"block\":\"minecraft:cobblestone\"}");
        Result result = new Result();
        TaskRunner runner = runner(companion, result);
        runner.start(build(wall, h, 4, 2, 6, Direction.SOUTH), "build_structure");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.FAILED, "should fail: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.startsWith("missing materials:"), "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 200)
    public void siteProblemFails(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(6, 2, 6, Blocks.CHEST);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.COBBLESTONE, 64));
        Blueprint wall = generator(
                "{\"type\":\"wall\",\"length\":5,\"height\":3,\"block\":\"minecraft:cobblestone\"}");
        Result result = new Result();
        TaskRunner runner = runner(companion, result);
        runner.start(build(wall, h, 4, 2, 6, Direction.SOUTH), "build_structure");
        String at = coords(h.absolutePos(new BlockPos(6, 2, 6)));
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.FAILED, "should fail: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.contains("the site isn't clear: minecraft:chest at " + at),
                    "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 3000)
    public void clearsAndFills(GameTestHelper h) {
        GameTestSupport.platformAt(h, 3, 16);
        h.setBlock(5, 4, 5, Blocks.TALL_GRASS);
        h.setBlock(6, 3, 6, Blocks.AIR);
        h.setBlock(6, 2, 6, Blocks.STONE);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 4, 2);
        companion.inventory().addItem(new ItemStack(Items.COBBLESTONE, 2));
        companion.inventory().addItem(new ItemStack(Items.DIRT, 3));
        Blueprint blueprint = parse("""
                {
                  "name": "hut",
                  "palette": { "C": "minecraft:cobblestone" },
                  "ops": [
                    { "op": "box", "from": [0,0,0], "to": [0,0,0], "block": "C" },
                    { "op": "box", "from": [1,0,1], "to": [1,0,1], "block": "C" }
                  ]
                }
                """);
        Result result = new Result();
        TaskRunner runner = runner(companion, result);
        runner.start(build(blueprint, h, 5, 4, 5, Direction.SOUTH), "build_structure");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(h.getBlockState(new BlockPos(5, 4, 5)).is(Blocks.COBBLESTONE), "grass replaced");
            h.assertTrue(h.getBlockState(new BlockPos(6, 3, 6)).is(Blocks.DIRT), "hole filled with dirt");
            h.assertTrue(h.getBlockState(new BlockPos(6, 4, 6)).is(Blocks.COBBLESTONE), "block above the foundation");
            h.assertTrue(result.summary.contains("2 placed"), "summary was " + result.summary);
            h.assertTrue(result.summary.contains("1 cleared"), "summary was " + result.summary);
            h.assertTrue(result.summary.contains("1 foundation"), "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 4000)
    public void takesOnlyShortfall(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.OAK_PLANKS, 10));
        h.setBlock(1, 2, 4, Blocks.CHEST);
        var near = (net.minecraft.world.level.block.entity.ChestBlockEntity)
                h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(1, 2, 4)));
        near.setItem(0, new ItemStack(Items.OAK_PLANKS, 10));
        near.setChanged();
        h.setBlock(1, 2, 12, Blocks.CHEST);
        var far = (net.minecraft.world.level.block.entity.ChestBlockEntity)
                h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(1, 2, 12)));
        far.setItem(0, new ItemStack(Items.OAK_PLANKS, 20));
        far.setChanged();
        Blueprint platform = generator(
                "{\"type\":\"platform\",\"width\":5,\"depth\":5,\"block\":\"minecraft:oak_planks\"}");
        Result result = new Result();
        TaskRunner runner = runner(companion, result);
        runner.start(build(platform, h, 5, 2, 5, Direction.SOUTH), "build_structure");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(near.getItem(0).isEmpty(), "the nearest chest must be emptied first: " + near.getItem(0));
            h.assertTrue(far.getItem(0).getCount() == 15, "the far chest keeps 15: " + far.getItem(0));
            h.assertTrue(h.getBlockState(new BlockPos(9, 2, 9)).is(Blocks.OAK_PLANKS), "the platform is built");
        });
    }

    @GameTest(maxTicks = 200)
    public void outOfReachFails(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.STONE_BRICKS, 64));
        Blueprint tower = generator(
                "{\"type\":\"tower\",\"size\":3,\"height\":8,\"walls\":\"minecraft:stone_bricks\"}");
        Result result = new Result();
        TaskRunner runner = runner(companion, result);
        runner.start(build(tower, h, 6, 2, 6, Direction.SOUTH), "build_structure");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.FAILED, "should fail: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.contains("out of reach"), "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 2000)
    public void stepsAsideToPlace(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 3, 2, 3);
        companion.inventory().addItem(new ItemStack(Items.OAK_PLANKS, 9));
        Blueprint platform = generator(
                "{\"type\":\"platform\",\"width\":3,\"depth\":3,\"block\":\"minecraft:oak_planks\"}");
        Result result = new Result();
        TaskRunner runner = runner(companion, result);
        runner.start(build(platform, h, 2, 2, 2, Direction.SOUTH), "build_structure");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(h.getBlockState(new BlockPos(3, 2, 3)).is(Blocks.OAK_PLANKS),
                    "the block under the companion was " + h.getBlockState(new BlockPos(3, 2, 3)));
        });
    }

    @GameTest(maxTicks = 4000)
    public void progressNotices(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.COBBLESTONE, 40));
        Blueprint platform = generator(
                "{\"type\":\"platform\",\"width\":8,\"depth\":5,\"block\":\"minecraft:cobblestone\"}");
        Result result = new Result();
        TaskRunner runner = runner(companion, result);
        runner.start(build(platform, h, 4, 2, 4, Direction.SOUTH), "build_structure");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(result.notices.size() == 3, "notices were " + result.notices);
            h.assertTrue(result.notices.get(0).contains("25%"), "first notice: " + result.notices.get(0));
            h.assertTrue(result.notices.get(1).contains("50%"), "second notice: " + result.notices.get(1));
            h.assertTrue(result.notices.get(2).contains("75%"), "third notice: " + result.notices.get(2));
            h.assertTrue(result.notices.get(0).startsWith("building platform: "), "notice text: " + result.notices.get(0));
        });
    }

    @GameTest(maxTicks = 3000)
    public void foreignChangeNotBroken(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.OAK_PLANKS, 5));
        Blueprint wall = generator(
                "{\"type\":\"wall\",\"length\":5,\"height\":1,\"block\":\"minecraft:oak_planks\"}");
        Result result = new Result();
        TaskRunner runner = runner(companion, result);
        runner.start(build(wall, h, 5, 2, 5, Direction.SOUTH), "build_structure");
        int[] ticks = {0};
        h.succeedWhen(() -> {
            runner.tick();
            if (++ticks[0] == 2) {
                h.setBlock(9, 2, 5, Blocks.GLASS);
            }
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.FAILED, "should fail: " + result.status + " " + result.summary);
            String at = coords(h.absolutePos(new BlockPos(9, 2, 5)));
            h.assertTrue(result.summary.equals(at + " is taken by minecraft:glass"), "summary was " + result.summary);
            h.assertTrue(h.getBlockState(new BlockPos(9, 2, 5)).is(Blocks.GLASS), "the glass must stay");
        });
    }

    @GameTest
    public void gableRoofHasStandingGround(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        Blueprint house = generator("""
                { "type": "house", "width": 7, "depth": 7,
                  "walls": "minecraft:oak_planks", "floor": "minecraft:cobblestone", "roof": "minecraft:oak_stairs" }
                """);
        BlockPos origin = h.absolutePos(new BlockPos(4, 2, 4));
        List<Placement.PlacedSpec> specs = Placement.at(house, origin, Rotation.NONE).specs();
        BlockPos outOfReach = dev.friendai.task.build.Reach.firstOutOfReach(h.getLevel(), specs, origin);
        h.assertTrue(outOfReach == null, "the gable roof must be reachable from its own walls, but " + outOfReach
                + " is not");
        h.succeed();
    }

    @GameTest
    public void tallTowerStaysOutOfReach(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        Blueprint tower = generator(
                "{\"type\":\"tower\",\"size\":3,\"height\":8,\"walls\":\"minecraft:stone_bricks\"}");
        BlockPos origin = h.absolutePos(new BlockPos(6, 2, 6));
        List<Placement.PlacedSpec> specs = Placement.at(tower, origin, Rotation.NONE).specs();
        h.assertTrue(dev.friendai.task.build.Reach.firstOutOfReach(h.getLevel(), specs, origin) != null,
                "a tower without scaffolding is out of reach");
        h.succeed();
    }

    @GameTest(maxTicks = 2000)
    public void doorHalvesShareOrientation(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.COBBLESTONE, 4));
        companion.inventory().addItem(new ItemStack(Items.OAK_DOOR, 1));
        Blueprint blueprint = parse("""
                {
                  "palette": { "C": "minecraft:cobblestone" },
                  "ops": [
                    { "op": "box", "from": [0,0,0], "to": [0,0,0], "block": "C" },
                    { "op": "door", "at": [0,1,0], "facing": "north", "block": "minecraft:oak_door" }
                  ]
                }
                """);
        Result result = new Result();
        TaskRunner runner = runner(companion, result);
        runner.start(build(blueprint, h, 4, 2, 4, Direction.SOUTH), "build_structure");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(h.getBlockState(new BlockPos(4, 3, 4)).equals(state("minecraft:oak_door[facing=north,half=lower]")),
                    "the lower half was " + h.getBlockState(new BlockPos(4, 3, 4)));
            h.assertTrue(h.getBlockState(new BlockPos(4, 4, 4)).equals(state("minecraft:oak_door[facing=north,half=upper]")),
                    "the upper half was " + h.getBlockState(new BlockPos(4, 4, 4)));
        });
    }

    @GameTest(maxTicks = 2000)
    public void bedHalvesMatchBlueprint(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.COBBLESTONE, 4));
        companion.inventory().addItem(new ItemStack(Items.BED.pick(DyeColor.RED), 1));
        Blueprint blueprint = parse("""
                {
                  "palette": { "C": "minecraft:cobblestone" },
                  "ops": [
                    { "op": "box", "from": [0,0,0], "to": [1,0,1], "block": "C" },
                    { "op": "box", "from": [0,1,0], "to": [0,1,0], "block": "minecraft:red_bed[facing=south,part=foot]" },
                    { "op": "box", "from": [0,1,1], "to": [0,1,1], "block": "minecraft:red_bed[facing=south,part=head]" }
                  ]
                }
                """);
        Result result = new Result();
        TaskRunner runner = runner(companion, result);
        runner.start(build(blueprint, h, 4, 2, 4, Direction.SOUTH), "build_structure");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(h.getBlockState(new BlockPos(4, 3, 4)).equals(state("minecraft:red_bed[facing=south,part=foot]")),
                    "the foot was " + h.getBlockState(new BlockPos(4, 3, 4)));
            h.assertTrue(h.getBlockState(new BlockPos(4, 3, 5)).equals(state("minecraft:red_bed[facing=south,part=head]")),
                    "the head was " + h.getBlockState(new BlockPos(4, 3, 5)));
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos neighbor = new BlockPos(4, 3, 4).relative(direction);
                if (neighbor.equals(new BlockPos(4, 3, 5))) {
                    continue;
                }
                BlockState stray = h.getBlockState(neighbor);
                h.assertFalse(stray.is(RED_BED), "a stray bed part was left at " + neighbor + ": " + stray);
            }
        });
    }

    @GameTest
    public void doorUpperHalfFollowsTheLower(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerLevel level = h.getLevel();
        BlockPos lower = h.absolutePos(new BlockPos(4, 3, 4));
        level.setBlock(lower, state("minecraft:oak_door[facing=south,half=lower]"), Block.UPDATE_NONE);
        level.setBlock(lower.above(), state("minecraft:oak_door[facing=south,half=upper]"), Block.UPDATE_NONE);
        Parts.reconcile(level, lower, state("minecraft:oak_door[facing=north,half=lower]"));
        h.assertBlockState(new BlockPos(4, 3, 4), state("minecraft:oak_door[facing=north,half=lower]"));
        h.assertBlockState(new BlockPos(4, 4, 4), state("minecraft:oak_door[facing=north,half=upper]"));
        h.succeed();
    }

    @GameTest
    public void strayBedHeadMovesToTheBlueprint(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerLevel level = h.getLevel();
        BlockPos foot = h.absolutePos(new BlockPos(4, 3, 4));
        level.setBlock(foot, state("minecraft:red_bed[facing=south,part=foot]"), Block.UPDATE_NONE);
        level.setBlock(foot.south(), state("minecraft:red_bed[facing=south,part=head]"), Block.UPDATE_NONE);
        Parts.reconcile(level, foot, state("minecraft:red_bed[facing=west,part=foot]"));
        h.assertBlockState(new BlockPos(4, 3, 4), state("minecraft:red_bed[facing=west,part=foot]"));
        h.assertBlockState(new BlockPos(3, 3, 4), state("minecraft:red_bed[facing=west,part=head]"));
        h.assertBlockState(new BlockPos(4, 3, 5), Blocks.AIR.defaultBlockState());
        h.succeed();
    }

    @GameTest
    public void defaultOriginLeavesRoomForTheWholeStructure(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        var owner = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        Blueprint blueprint = parse("""
                {
                  "palette": { "C": "minecraft:cobblestone" },
                  "ops": [ { "op": "box", "from": [0,0,0], "to": [2,0,2], "block": "C" } ]
                }
                """);
        BlockPos ownerPos = owner.blockPosition();
        Placement.PlacedBlueprint south = Placement.of(blueprint, null, null, companion);
        equal(ownerPos.getZ() + 3, south.origin().getZ(), "the near edge starts three blocks ahead");
        equal(ownerPos.getX() - 1, south.origin().getX(), "centred across the owner");
        equal(Rotation.CLOCKWISE_180, south.rotation(), "the front faces the owner");

        owner.setYRot(90F);
        Placement.PlacedBlueprint west = Placement.of(blueprint, null, null, companion);
        equal(ownerPos.getX() - 5, west.origin().getX(), "west-facing: the whole depth lies ahead");
        equal(ownerPos.getZ() - 1, west.origin().getZ(), "west-facing: centred across the owner");
        equal(Rotation.COUNTERCLOCKWISE_90, west.rotation(), "the front faces the owner");
        h.succeed();
    }

    private static Site.SiteReport site(
            GameTestHelper h, CompanionEntity companion, int x, int y, int z, String block, boolean clear) {
        Blueprint blueprint = parse("""
                {
                  "palette": { "C": "%s" },
                  "ops": [ { "op": "box", "from": [0,0,0], "to": [0,0,0], "block": "C" } ]
                }
                """.formatted(block));
        return Site.check(
                h.getLevel(),
                Placement.of(blueprint, h.absolutePos(new BlockPos(x, y, z)), Direction.SOUTH, companion),
                companion,
                clear);
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
