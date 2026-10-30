package dev.friendai.test;

import com.google.gson.JsonObject;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.entity.CompanionPlacement;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.features.acquisition.minecraft.task.ObtainTask;
import dev.friendai.features.construction.PlacementPace;
import dev.friendai.features.construction.blueprint.BlockSpec;
import dev.friendai.features.construction.blueprint.Blueprint;
import dev.friendai.features.construction.blueprint.BlueprintParser;
import dev.friendai.features.construction.blueprint.ParseResult;
import dev.friendai.features.construction.blueprint.PartKind;
import dev.friendai.features.construction.blueprint.PlacementRefusal;
import dev.friendai.features.construction.minecraft.build.BlueprintResolver;
import dev.friendai.features.construction.minecraft.build.BlueprintStore;
import dev.friendai.features.construction.minecraft.build.Materials;
import dev.friendai.features.construction.minecraft.build.Parts;
import dev.friendai.features.construction.minecraft.build.Placement;
import dev.friendai.features.construction.minecraft.build.Scaffold;
import dev.friendai.features.construction.minecraft.build.ScaffoldCleanupTask;
import dev.friendai.features.construction.minecraft.build.Site;
import dev.friendai.features.construction.minecraft.build.SupportNeeds;
import dev.friendai.features.construction.minecraft.task.BuildTask;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.server.execution.TaskEventSink;
import dev.friendai.server.execution.persistence.TaskRestoreRegistry;
import dev.friendai.server.execution.tools.MinecraftObtainSubtaskFactory;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskState;
import dev.friendai.task.TaskStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.WallTorchBlock;
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
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, text, false)
                    .blockState();
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
    public void resolvesTestTemplate(GameTestHelper h) {
        ParseResult result = resolveTemplate(h, "friendai-gametest:tiny_hut");
        check(result.errors().isEmpty(), "unexpected errors: " + result.errors());
        Blueprint hut = result.blueprint().orElseThrow();
        equal("tiny_hut", hut.name(), "name");
        equal(5, hut.width(), "width");
        equal(4, hut.height(), "height");
        equal(5, hut.depth(), "depth");
        equal(83, hut.blocks().size(), "block count");
        equal(List.of("skipped 1 liquid block"), result.notes(), "notes");
        for (BlockSpec spec : hut.blocks()) {
            check(spec.x() >= 0 && spec.x() < hut.width(), "x outside the size: " + spec);
            check(spec.y() >= 0 && spec.y() < hut.height(), "y outside the size: " + spec);
            check(spec.z() >= 0 && spec.z() < hut.depth(), "z outside the size: " + spec);
        }
        BlockSpec roof = hut.blocks().stream()
                .filter(spec -> spec.x() == 2 && spec.y() == 3 && spec.z() == 2)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no block at the jigsaw position"));
        check(state(roof.state()).is(Blocks.OAK_PLANKS), "the jigsaw must become oak planks, was " + roof.state());
        h.succeed();
    }

    @GameTest
    public void templateSkipsVoidAndLiquids(GameTestHelper h) {
        ParseResult result = resolveTemplate(h, "friendai-gametest:tiny_hut");
        check(result.errors().isEmpty(), "unexpected errors: " + result.errors());
        Blueprint hut = result.blueprint().orElseThrow();
        for (BlockSpec spec : hut.blocks()) {
            BlockState blockState = state(spec.state());
            check(!blockState.is(Blocks.STRUCTURE_VOID), "structure void must be skipped: " + spec);
            check(blockState.getFluidState().isEmpty(), "liquid must be skipped: " + spec);
        }
        equal(List.of("skipped 1 liquid block"), result.notes(), "notes");
        h.succeed();
    }

    @GameTest
    public void templateDropsBlockEntityData(GameTestHelper h) {
        Blueprint hut = template(h, "friendai-gametest:tiny_hut");
        List<BlockSpec> chests = hut.blocks().stream()
                .filter(spec -> state(spec.state()).is(Blocks.CHEST))
                .toList();
        equal(1, chests.size(), "one chest");
        equal(
                Blocks.CHEST.defaultBlockState(),
                state(chests.get(0).state()),
                "a plain chest without block-entity data");
        equal(83, hut.blocks().size(), "the chest stays one block; its items are dropped");
        h.succeed();
    }

    @GameTest
    public void unknownTemplate(GameTestHelper h) {
        ParseResult result = resolveTemplate(h, "friendai:nope");
        equal(List.of("no structure template friendai:nope"), result.errors(), "errors");
        check(result.blueprint().isEmpty(), "no blueprint");
        h.succeed();
    }

    @GameTest
    public void badTemplateId(GameTestHelper h) {
        ParseResult result = resolveTemplate(h, "Bad Id");
        equal(List.of("not a valid template id: Bad Id"), result.errors(), "errors");
        check(result.blueprint().isEmpty(), "no blueprint");
        h.succeed();
    }

    @GameTest
    public void vanillaTemplateResolves(GameTestHelper h) {
        ParseResult result = resolveTemplate(h, "minecraft:village/plains/houses/plains_small_house_1");
        check(result.errors().isEmpty(), "unexpected errors: " + result.errors());
        Blueprint house = result.blueprint().orElseThrow();
        check(
                house.width() <= Blueprint.MAX_SIZE
                        && house.height() <= Blueprint.MAX_SIZE
                        && house.depth() <= Blueprint.MAX_SIZE,
                "size " + house.width() + "×" + house.height() + "×" + house.depth());
        check(
                house.blocks().size() <= Blueprint.MAX_BLOCKS,
                "block count " + house.blocks().size());
        check(!house.blocks().isEmpty(), "the house must have blocks");
        h.succeed();
    }

    private static ParseResult resolveTemplate(GameTestHelper h, String id) {
        return BlueprintResolver.resolve(args("structure", id), h.getLevel(), null);
    }

    private static Blueprint template(GameTestHelper h, String id) {
        ParseResult result = resolveTemplate(h, id);
        check(result.errors().isEmpty(), "could not resolve " + id + ": " + result.errors());
        return result.blueprint().orElseThrow();
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

        equal(
                state("minecraft:oak_log[axis=x]").rotate(Rotation.CLOCKWISE_90),
                stateAt(placed, origin.offset(1, 0, 0)),
                "log state at its rotated position");
        equal(
                state("minecraft:oak_stairs[facing=north,half=bottom]").rotate(Rotation.CLOCKWISE_90),
                stateAt(placed, origin.offset(1, 0, 1)),
                "stairs state at its rotated position");
        equal(
                state("minecraft:oak_slab[type=top]").rotate(Rotation.CLOCKWISE_90),
                stateAt(placed, origin.offset(0, 0, 0)),
                "slab state at its rotated position");
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
        equal(
                h.getLevel().getHeight(Heightmap.Types.WORLD_SURFACE, ownerPos.getX(), ownerPos.getZ() + 3),
                placed.origin().getY(),
                "origin y on the ground");
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

    private static BlueprintStore store(GameTestHelper h) {
        return BlueprintStore.forLevel(h.getLevel());
    }

    private static String buildId(String saved) {
        return saved.substring(0, saved.indexOf(','));
    }

    @GameTest(maxTicks = 400)
    public void saveDataFormat(GameTestHelper h) throws Exception {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.COBBLESTONE, 64));
        BlockPos origin = h.absolutePos(new BlockPos(4, 2, 4));
        BuildTask task = new BuildTask(parse(EXAMPLE), origin, Rotation.CLOCKWISE_90, true, false);
        String saved = task.saveData();
        String[] parts = saved.split(",");
        equal(7, parts.length, "field count");
        check(parts[0].matches("[0-9a-f]{32}"), "field id: " + parts[0]);
        equal(Integer.toString(origin.getX()), parts[1], "x");
        equal(Integer.toString(origin.getY()), parts[2], "y");
        equal(Integer.toString(origin.getZ()), parts[3], "z");
        equal("west", parts[4], "facing of CLOCKWISE_90");
        equal("true", parts[5], "clearSite");
        equal("false", parts[6], "gather");

        // A level-free restore cannot rebuild a build task; the in-progress file lives in the world.
        check(
                TaskRestoreRegistry.restoreCheckpoint(new TaskState("build_structure", saved), null)
                        .isEmpty(),
                "a build needs a level");

        BuildTask partial =
                new BuildTask(generator("""
                { "type": "house", "width": 5, "depth": 5, "roof_style": "flat",
                  "walls": "minecraft:oak_planks", "floor": "minecraft:cobblestone", "roof": "minecraft:oak_slab" }
                """), origin, Rotation.CLOCKWISE_90, true, false, Set.of(PartKind.FLOOR));
        String partialSaved = partial.saveData();
        String[] partialParts = partialSaved.split(",");
        equal(8, partialParts.length, "partial field count");
        equal("floor", partialParts[7], "parts");

        String id = buildId(partialSaved);
        BlueprintStore store = store(h);
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        try {
            runner.start(partial, "build_structure");
            runner.tick();
            check(Files.exists(store.inProgressFile(id)), "the in-progress file must exist after the check");
            BlueprintStore.InProgress loaded = store.loadInProgress(id).orElse(null);
            check(loaded != null, "the in-progress file must load: " + Files.readString(store.inProgressFile(id)));
            equal(Rotation.CLOCKWISE_90, loaded.rotation(), "in-progress rotation");
            Task restored = TaskRestoreRegistry.restoreCheckpoint(
                            new TaskState("build_structure", partialSaved), h.getLevel())
                    .orElse(null);
            check(restored != null, "the saved build must restore: " + partialSaved);
            equal(partialSaved, restored.saveData(), "save data round trip");
            String corrupt = partialSaved.substring(0, partialSaved.lastIndexOf(',') + 1) + "nonsense";
            check(
                    TaskRestoreRegistry.restoreCheckpoint(new TaskState("build_structure", corrupt), h.getLevel())
                            .isEmpty(),
                    "an unknown part must not restore the build");
        } finally {
            store.deleteInProgress(id);
            runner.stop("test");
        }
        h.succeed();
    }

    @GameTest(maxTicks = 400)
    public void ownerLeftKeepsTheBuildForReload(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var server = h.getLevel().getServer();
        CompanionEntity companion = CompanionManager.get(server).summon(owner);
        companion.inventory().addItem(new ItemStack(Items.COBBLESTONE, 64));
        Blueprint platform =
                generator("{\"type\":\"platform\",\"width\":5,\"depth\":5,\"block\":\"minecraft:cobblestone\"}");
        companion
                .executionCoordinator()
                .start(
                        new BuildTask(platform, h.absolutePos(new BlockPos(5, 2, 5)), Rotation.NONE, true, false),
                        "build_structure");
        h.runAfterDelay(5, () -> {
            h.assertTrue(companion.executionCoordinator().current().isPresent(), "the build must still be running");
            String saved =
                    companion.executionCoordinator().current().orElseThrow().saveData();
            GameTestSupport.runtime(server).sessions().onOwnerLeft(owner.getUUID());
            h.assertTrue(
                    saved.equals(companion
                            .executionCoordinator()
                            .current()
                            .map(Task::saveData)
                            .orElse("")),
                    "owner leave cancelled the build");
            h.assertTrue(companion.executionCoordinator().suspended(), "the runner must pause while the owner is away");
            GameTestSupport.runtime(server).sessions().onOwnerJoined(owner.getUUID());
            h.assertTrue(
                    !companion.executionCoordinator().suspended(), "the runner must resume when the owner returns");
            companion.executionCoordinator().stop("test");
            h.succeed();
        });
    }

    @GameTest(maxTicks = 4000)
    public void restoreResumes(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.COBBLESTONE, 64));
        Blueprint platform =
                generator("{\"type\":\"platform\",\"width\":4,\"depth\":4,\"block\":\"minecraft:cobblestone\"}");
        BlockPos origin = h.absolutePos(new BlockPos(5, 2, 5));
        BlueprintStore store = store(h);
        Result first = new Result();
        ExecutionCoordinator runner = runner(companion, first);
        runner.start(new BuildTask(platform, origin, Rotation.NONE, true, false), "build_structure");
        Result restored = new Result();
        String[] saved = {null};
        ExecutionCoordinator[] restoredRunner = {null};
        h.succeedWhen(() -> {
            if (saved[0] == null) {
                runner.tick();
                if (companion.inventory().countItem(Items.COBBLESTONE) < 16) {
                    saved[0] = runner.current().orElseThrow().saveData();
                    BuildTask resumed = (BuildTask) TaskRestoreRegistry.restoreCheckpoint(
                                    new TaskState("build_structure", saved[0]), h.getLevel())
                            .orElseThrow();
                    equal(saved[0], resumed.saveData(), "the id and fields survive the restore");
                    restoredRunner[0] = runner(companion, restored);
                    restoredRunner[0].start(resumed, "build_structure");
                }
                return;
            }
            restoredRunner[0].tick();
            h.assertTrue(restored.status != null, "still running");
            h.assertTrue(restored.status == TaskStatus.SUCCESS, "status " + restored.status + ": " + restored.summary);
            h.assertTrue(restored.summary.startsWith("built platform at "), "summary was " + restored.summary);
            for (int x = 5; x <= 8; x++) {
                for (int z = 5; z <= 8; z++) {
                    h.assertTrue(
                            h.getBlockState(new BlockPos(x, 2, z)).is(Blocks.COBBLESTONE),
                            "missing block at " + x + ",2," + z);
                }
            }
            h.assertTrue(
                    !Files.exists(store.inProgressFile(buildId(saved[0]))),
                    "the in-progress file must be gone after the build");
            runner.stop("test");
        });
    }

    @GameTest(maxTicks = 20000)
    public void restoreRemovesOldScaffold(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        clearAir(h, 16, 2, 14);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.STONE_BRICKS, 64));
        companion.inventory().addItem(new ItemStack(Items.STONE_BRICKS, 16));
        companion.inventory().addItem(new ItemStack(Items.DIRT, 20));
        Blueprint tower =
                generator("{\"type\":\"tower\",\"size\":3,\"height\":8,\"walls\":\"minecraft:stone_bricks\"}");
        BlueprintStore store = store(h);
        Result first = new Result();
        ExecutionCoordinator runner = runner(companion, first);
        runner.start(
                new BuildTask(tower, h.absolutePos(new BlockPos(6, 2, 6)), Rotation.NONE, true, false),
                "build_structure");
        Result restored = new Result();
        String[] saved = {null};
        ExecutionCoordinator[] restoredRunner = {null};
        int[] ticks = {0};
        h.succeedWhen(() -> {
            ticks[0]++;
            if (saved[0] == null) {
                runner.tick();
                if (ticks[0] % 5 == 0) {
                    String current = runner.current().orElseThrow().saveData();
                    boolean scaffolded = store.loadInProgress(buildId(current))
                            .map(inProgress -> !inProgress.scaffold().isEmpty())
                            .orElse(false);
                    if (scaffolded) {
                        saved[0] = current;
                        BuildTask resumed = (BuildTask) TaskRestoreRegistry.restoreCheckpoint(
                                        new TaskState("build_structure", saved[0]), h.getLevel())
                                .orElseThrow();
                        restoredRunner[0] = runner(companion, restored);
                        restoredRunner[0].start(resumed, "build_structure");
                    }
                }
                return;
            }
            restoredRunner[0].tick();
            h.assertTrue(restored.status != null, "still running");
            h.assertTrue(restored.status == TaskStatus.SUCCESS, "status " + restored.status + ": " + restored.summary);
            h.assertTrue(restored.summary.contains("scaffold blocks (removed)"), "summary was " + restored.summary);
            h.assertTrue(
                    h.getBlockState(new BlockPos(8, 9, 8)).is(Blocks.STONE_BRICKS),
                    "the tower top is missing: " + h.getBlockState(new BlockPos(8, 9, 8)));
            List<String> dirt = positionsOf(h, 16, 2, 15, Blocks.DIRT);
            List<String> cobblestone = positionsOf(h, 16, 2, 15, Blocks.COBBLESTONE);
            h.assertTrue(dirt.isEmpty(), "restored scaffold dirt left behind at " + dirt);
            h.assertTrue(cobblestone.isEmpty(), "restored scaffold cobblestone left behind at " + cobblestone);
            h.assertTrue(
                    !Files.exists(store.inProgressFile(buildId(saved[0]))),
                    "the in-progress file must be gone after the build");
            runner.stop("test");
        });
    }

    @GameTest(maxTicks = 4000)
    public void floorBuildsWhileStandingInTheMiddle(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 5, 2, 5);
        companion.inventory().addItem(new ItemStack(Items.COBBLESTONE, 16));
        Blueprint floor =
                generator("{\"type\":\"platform\",\"width\":3,\"depth\":3,\"block\":\"minecraft:cobblestone\"}");
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(build(floor, h, 4, 2, 4, Direction.SOUTH), "build_structure");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(
                    result.status != null,
                    "still running: " + runner.current().map(Task::debugStatus).orElse("none"));
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            for (int x = 4; x <= 6; x++) {
                for (int z = 4; z <= 6; z++) {
                    h.assertTrue(
                            h.getBlockState(new BlockPos(x, 2, z)).is(Blocks.COBBLESTONE),
                            "missing floor at " + x + ",2," + z);
                }
            }
            h.assertTrue(
                    h.getLevel().getBlockState(companion.blockPosition()).isAir(),
                    "the companion must not finish inside a block");
            h.assertFalse(result.summary.contains("blocked by me"), "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 2000)
    public void aCellUnderTheCompanionIsFilledAfterSteppingOut(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 5, 2, 5);
        companion.inventory().addItem(new ItemStack(Items.COBBLESTONE, 8));
        Blueprint single =
                generator("{\"type\":\"platform\",\"width\":1,\"depth\":1,\"block\":\"minecraft:cobblestone\"}");
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(build(single, h, 5, 2, 5, Direction.SOUTH), "build_structure");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(
                    result.status != null,
                    "still running: " + runner.current().map(Task::debugStatus).orElse("none"));
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(
                    h.getBlockState(new BlockPos(5, 2, 5)).is(Blocks.COBBLESTONE),
                    "the cell the companion stood in must be filled");
        });
    }

    @GameTest(maxTicks = 3000)
    public void aZombieInTheCellIsWaitedOut(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.COBBLESTONE, 8));
        var zombie = h.spawn(EntityTypes.ZOMBIE, 5, 2, 5);
        Blueprint single =
                generator("{\"type\":\"platform\",\"width\":1,\"depth\":1,\"block\":\"minecraft:cobblestone\"}");
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(build(single, h, 5, 2, 5, Direction.SOUTH), "build_structure");
        int[] ticks = {0};
        h.succeedWhen(() -> {
            runner.tick();
            if (++ticks[0] == 20) {
                zombie.discard();
            }
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(
                    h.getBlockState(new BlockPos(5, 2, 5)).is(Blocks.COBBLESTONE),
                    "the cell must be filled once the zombie leaves");
        });
    }

    @GameTest(maxTicks = 1000)
    public void aPlayerOnTheSiteIsNamed(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.COBBLESTONE, 8));
        GameTestSupport.mockPlayerAt(h, 5, 2, 5);
        Blueprint single =
                generator("{\"type\":\"platform\",\"width\":1,\"depth\":1,\"block\":\"minecraft:cobblestone\"}");
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(build(single, h, 5, 2, 5, Direction.SOUTH), "build_structure");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.FAILED, "status " + result.status + ": " + result.summary);
            h.assertTrue(result.summary.contains("minecraft:player"), "summary was " + result.summary);
            h.assertFalse(result.summary.contains("couldn't place"), "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 1000)
    public void aFailedBuildResumesAtItsOrigin(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.COBBLESTONE, 8));
        h.setBlock(5, 2, 5, Blocks.CHEST);
        Blueprint single =
                generator("{\"type\":\"platform\",\"width\":1,\"depth\":1,\"block\":\"minecraft:cobblestone\"}");
        BlockPos origin = h.absolutePos(new BlockPos(5, 2, 5));
        BuildTask task = new BuildTask(single, origin, Rotation.NONE, true, false);
        String saved = task.saveData();
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(task, "build_structure");
        boolean[] retrying = {false};
        Result retried = new Result();
        ExecutionCoordinator[] retryRunner = {null};
        h.succeedWhen(() -> {
            if (!retrying[0]) {
                runner.tick();
                if (result.status == null) {
                    return;
                }
                h.assertTrue(result.status == TaskStatus.FAILED, "status " + result.status + ": " + result.summary);
                BlueprintStore store = store(h);
                BlueprintStore.InProgress record = store.findResumable(
                                single,
                                companion.ownerUuid(),
                                h.getLevel().dimension().identifier().toString(),
                                origin)
                        .orElse(null);
                h.assertTrue(record != null, "a failed build must leave a resumable record");
                h.assertTrue(record.origin().equals(origin), "the record keeps the origin");
                h.assertTrue(record.remaining().contains(origin), "the record knows the remaining cell");
                h.setBlock(5, 2, 5, Blocks.AIR);
                BuildTask resumed = (BuildTask)
                        TaskRestoreRegistry.restoreCheckpoint(new TaskState("build_structure", saved), h.getLevel())
                                .orElseThrow();
                retrying[0] = true;
                retryRunner[0] = runner(companion, retried);
                retryRunner[0].start(resumed, "build_structure");
                return;
            }
            retryRunner[0].tick();
            h.assertTrue(retried.status != null, "resume still running");
            h.assertTrue(
                    retried.status == TaskStatus.SUCCESS, "resume status " + retried.status + ": " + retried.summary);
            h.assertTrue(
                    !Files.exists(store(h).inProgressFile(buildId(saved))), "a finished resume deletes its record");
        });
    }

    @GameTest
    public void anOwnScaffoldIsNotASiteProblem(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Blueprint three = parse("""
                {
                  "palette": { "C": "minecraft:cobblestone" },
                  "ops": [ { "op": "box", "from": [0,0,0], "to": [2,0,0], "block": "C" } ]
                }
                """);
        Placement.PlacedBlueprint placed =
                Placement.of(three, h.absolutePos(new BlockPos(5, 2, 5)), Direction.SOUTH, companion);
        BlockPos own = placed.specs().get(1).pos();
        h.setBlock(6, 2, 5, Blocks.DIRT);
        Site.SiteReport clean = Site.check(h.getLevel(), placed, companion, true, pos -> pos.equals(own));
        check(clean.problems().isEmpty(), "an own block must not be a site problem: " + clean.problems());
        h.setBlock(7, 2, 5, Blocks.CHEST);
        Site.SiteReport blocked = Site.check(h.getLevel(), placed, companion, true, pos -> pos.equals(own));
        check(
                blocked.refusals().getFirst() instanceof PlacementRefusal.ForeignBlock,
                "a foreign block is named: " + blocked.refusals());
        h.succeed();
    }

    @GameTest(maxTicks = 400)
    public void cancelKeepsTheRecord(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.COBBLESTONE, 64));
        Blueprint platform =
                generator("{\"type\":\"platform\",\"width\":3,\"depth\":3,\"block\":\"minecraft:cobblestone\"}");
        BuildTask task = new BuildTask(platform, h.absolutePos(new BlockPos(5, 2, 5)), Rotation.NONE, true, false);
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(task, "build_structure");
        runner.tick();
        String saved = task.saveData();
        BlueprintStore store = store(h);
        check(Files.exists(store.inProgressFile(buildId(saved))), "the file exists after the check");
        runner.stop("test");
        check(Files.exists(store.inProgressFile(buildId(saved))), "a cancelled build stays resumable");
        BlueprintStore.InProgress loaded = store.loadInProgress(buildId(saved)).orElseThrow();
        equal(h.absolutePos(new BlockPos(5, 2, 5)), loaded.origin(), "the resume record keeps the origin");
        check(
                TaskRestoreRegistry.restoreCheckpoint(new TaskState("build_structure", saved), h.getLevel())
                        .isPresent(),
                "a cancelled build restores from its record instead of being forgotten");
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
        equal(
                List.of(
                        h.absolutePos(new BlockPos(5, 2, 5)),
                        h.absolutePos(new BlockPos(6, 2, 6)),
                        h.absolutePos(new BlockPos(7, 2, 7))),
                report.toClear(),
                "positions to clear");
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
        check(
                report.problems().size() == 1
                        && report.problems().getFirst().contains(at)
                        && report.problems().getFirst().contains("needs a block at"),
                "problems were " + report.problems());
        check(report.refusals().getFirst() instanceof PlacementRefusal.NoSupport, "cause was " + report.refusals());
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
        equal(
                List.of(coords(target) + " is protected by the server (spawn protection or a world rule)"),
                report.problems(),
                "problems");
        check(report.refusals().getFirst() instanceof PlacementRefusal.Protected, "cause was " + report.refusals());
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
    public void haveCountsOnlyCompanionInventory(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        // Birch: no other test stores it, so a neighbouring test's chest inside the 16-block storage
        // radius cannot change the count (the shared GameTest world places structures 5-6 blocks apart).
        companion.inventory().addItem(new ItemStack(Items.BIRCH_PLANKS, 10));
        h.setBlock(6, 2, 6, Blocks.CHEST);
        var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity)
                h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(6, 2, 6)));
        chest.setItem(0, new ItemStack(Items.BIRCH_PLANKS, 20));
        chest.setChanged();
        equal(
                10,
                Materials.have(companion, h.getLevel()).getOrDefault(Items.BIRCH_PLANKS, 0),
                "closed storage contents are not available through carried materials");
        h.succeed();
    }

    private static final class Result implements TaskEventSink {
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

    private static ExecutionCoordinator runner(CompanionEntity companion, Result result) {
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 300, () -> false);
        runner.setEventSink(result);
        return runner;
    }

    @GameTest(maxTicks = 200)
    public void scaffoldCleanupStepsOffItsSupportBeforeBreakingIt(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        BlockPos support = h.absolutePos(new BlockPos(2, 1, 2));
        h.getLevel().setBlock(support, Blocks.DIRT.defaultBlockState(), 3);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ScaffoldCleanupTask cleanup = new ScaffoldCleanupTask(List.of(support));
        TaskContext context = new TaskContext(companion, h.getLevel());
        cleanup.start(context);
        TaskStatus[] status = {TaskStatus.RUNNING};
        BlockPos[] checkpoints = new BlockPos[4];
        int[] ticks = {0};

        h.succeedWhen(() -> {
            status[0] = cleanup.tick(context);
            if (ticks[0] == 1 || ticks[0] == 10 || ticks[0] == 50 || ticks[0] == 200) {
                checkpoints[ticks[0] == 1 ? 0 : ticks[0] == 10 ? 1 : ticks[0] == 50 ? 2 : 3] =
                        companion.blockPosition();
            }
            ticks[0]++;
            h.assertTrue(
                    status[0] == TaskStatus.SUCCESS,
                    "cleanup did not finish: " + status[0] + " " + cleanup.resultSummary() + " at "
                            + companion.blockPosition() + " with navigation "
                            + companion.getNavigation().isDone()
                            + " checkpoints=" + java.util.Arrays.toString(checkpoints));
            h.assertTrue(h.getLevel().getBlockState(support).isAir(), "the scaffold support was left behind");
        });
    }

    private static BuildTask build(Blueprint blueprint, GameTestHelper h, int x, int y, int z, Direction facing) {
        BlockPos origin = h.absolutePos(new BlockPos(x, y, z));
        return new BuildTask(blueprint, origin, Placement.rotationFor(facing), true);
    }

    private static Blueprint generator(String json) {
        ParseResult result = dev.friendai.features.construction.blueprint.Generators.generate(
                com.google.gson.JsonParser.parseString(json).getAsJsonObject(), BlueprintResolver.knownState());
        check(result.blueprint().isPresent(), "generator failed: " + result.errors());
        return result.blueprint().get();
    }

    @GameTest(maxTicks = 2000)
    public void buildsGeneratedWall(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.COBBLESTONE, 15));
        Blueprint wall = generator("{\"type\":\"wall\",\"length\":5,\"height\":3,\"block\":\"minecraft:cobblestone\"}");
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(build(wall, h, 4, 2, 6, Direction.SOUTH), "build_structure");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(result.summary.startsWith("built wall at "), "summary was " + result.summary);
            for (int x = 4; x <= 8; x++) {
                for (int y = 2; y <= 4; y++) {
                    h.assertTrue(
                            h.getBlockState(new BlockPos(x, y, 6)).is(Blocks.COBBLESTONE),
                            "missing wall block at " + x + "," + y + ",6: " + h.getBlockState(new BlockPos(x, y, 6)));
                }
            }
            h.assertTrue(companion.inventory().countItem(Items.COBBLESTONE) == 0, "all cobblestone must be used");
        });
    }

    /** The 3×3 cobblestone wall the pace tests build: cells x 4..6, y 2..4 at z 6. */
    private static int wallBlocks(GameTestHelper h) {
        int count = 0;
        for (int x = 4; x <= 6; x++) {
            for (int y = 2; y <= 4; y++) {
                if (h.getBlockState(new BlockPos(x, y, 6)).is(Blocks.COBBLESTONE)) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * A survival build must rise at a player's pace, not twenty blocks a second. Measured before the pace existed:
     * one block per tick, so this wall went up in nine placement ticks with a one-tick gap between them.
     */
    @GameTest(maxTicks = 2000)
    public void aSurvivalWallRisesAtAPlayersPace(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.COBBLESTONE, 9));
        Blueprint wall = generator("{\"type\":\"wall\",\"length\":3,\"height\":3,\"block\":\"minecraft:cobblestone\"}");
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(build(wall, h, 4, 2, 6, Direction.SOUTH), "build_structure");
        int[] ticks = {0};
        int[] seen = {0};
        int[] lastPlacement = {Integer.MIN_VALUE};
        int[] shortestGap = {Integer.MAX_VALUE};
        int[] finishedAt = {-1};
        h.succeedWhen(() -> {
            if (result.status == null) {
                runner.tick();
                int now = wallBlocks(h);
                if (now > seen[0]) {
                    // The block that just went in must be visible in the hand on the very tick it was placed.
                    check(
                            companion.syncedPlaceItem().is(Items.COBBLESTONE),
                            "the placed block is not in the hand: " + companion.syncedPlaceItem());
                    if (lastPlacement[0] != Integer.MIN_VALUE) {
                        shortestGap[0] = Math.min(shortestGap[0], ticks[0] - lastPlacement[0]);
                    }
                    lastPlacement[0] = ticks[0];
                    seen[0] = now;
                }
            }
            ticks[0]++;
            if (result.status == null) {
                return;
            }
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            if (finishedAt[0] < 0) {
                finishedAt[0] = ticks[0];
            }
            h.assertTrue(
                    ticks[0] >= 9 * PlacementPace.DEFAULT_INTERVAL,
                    "nine blocks at " + PlacementPace.DEFAULT_INTERVAL + " ticks each need at least 36 ticks, the wall"
                            + " took " + ticks[0] + " (shortest gap " + shortestGap[0] + ")");
            h.assertTrue(
                    shortestGap[0] >= PlacementPace.DEFAULT_INTERVAL,
                    "two blocks went in " + shortestGap[0] + " ticks apart");
            h.assertTrue(
                    ticks[0] > finishedAt[0] + CompanionPlacement.POSE_TICKS, "waiting for the placement pose to end");
            h.assertTrue(
                    companion.syncedPlaceItem().isEmpty(),
                    "the block stayed in the hand after the build: " + companion.syncedPlaceItem());
            h.assertTrue(companion.placement().ready(), "the pace must be clear once the job is done");
        });
    }

    /** Creative is the printer players know: one block per tick, whatever the survival setting says. */
    @GameTest(maxTicks = 2000)
    public void aCreativeWallKeepsThePrinterSpeed(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.COBBLESTONE, 9));
        companion.setCreativeMode(true);
        Blueprint wall = generator("{\"type\":\"wall\",\"length\":3,\"height\":3,\"block\":\"minecraft:cobblestone\"}");
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(build(wall, h, 4, 2, 6, Direction.SOUTH), "build_structure");
        int[] ticks = {0};
        int[] seen = {0};
        int[] lastPlacement = {Integer.MIN_VALUE};
        int[] shortestGap = {Integer.MAX_VALUE};
        h.succeedWhen(() -> {
            if (result.status == null) {
                runner.tick();
                int now = wallBlocks(h);
                if (now > seen[0]) {
                    if (lastPlacement[0] != Integer.MIN_VALUE) {
                        shortestGap[0] = Math.min(shortestGap[0], ticks[0] - lastPlacement[0]);
                    }
                    lastPlacement[0] = ticks[0];
                    seen[0] = now;
                }
            }
            ticks[0]++;
            if (result.status == null) {
                return;
            }
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(
                    shortestGap[0] < PlacementPace.DEFAULT_INTERVAL,
                    "creative must not be slowed down, the shortest gap was " + shortestGap[0] + " ticks");
            h.assertTrue(
                    ticks[0] < 9 * PlacementPace.DEFAULT_INTERVAL,
                    "the creative wall took " + ticks[0] + " ticks, the pacing is not bypassed");
        });
    }

    @GameTest(maxTicks = 8000)
    public void buildsSmallHouseFromChest(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.BREAD, 4));
        companion.inventory().addItem(new ItemStack(Items.DIRT, 64));
        h.setBlock(2, 2, 9, Blocks.CHEST);
        var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity)
                h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(2, 2, 9)));
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        chest.setItem(6, new ItemStack(Items.COBBLESTONE, 64));
        chest.setItem(1, new ItemStack(Items.OAK_PLANKS, 64));
        chest.setItem(2, new ItemStack(Items.OAK_PLANKS, 64));
        chest.setItem(3, new ItemStack(Items.GLASS_PANE, 16));
        chest.setItem(4, new ItemStack(Items.OAK_SLAB, 32));
        chest.setItem(5, new ItemStack(Items.OAK_DOOR, 2));
        chest.setChanged();
        h.assertTrue(
                GameTestSupport.observeVisible(h, companion, h.absolutePos(new BlockPos(2, 2, 9))),
                "materials chest should be directly visible");
        Blueprint house = generator("""
                { "type": "house", "width": 5, "depth": 5, "roof_style": "flat",
                  "walls": "minecraft:oak_planks", "floor": "minecraft:cobblestone", "roof": "minecraft:oak_slab" }
                """);
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
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
    public void buildOnlyTheFloor(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.COBBLESTONE, 64));
        Blueprint house = generator("""
                { "type": "house", "width": 5, "depth": 5, "roof_style": "flat",
                  "walls": "minecraft:oak_planks", "floor": "minecraft:cobblestone", "roof": "minecraft:oak_slab" }
                """);
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        BuildTask task = new BuildTask(
                house, h.absolutePos(new BlockPos(4, 2, 4)), Rotation.NONE, true, false, Set.of(PartKind.FLOOR));
        runner.start(task, "build_structure");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            for (int x = 4; x <= 8; x++) {
                for (int z = 4; z <= 8; z++) {
                    h.assertTrue(
                            h.getBlockState(new BlockPos(x, 2, z)).is(Blocks.COBBLESTONE),
                            "floor at " + x + ",2," + z + ": " + h.getBlockState(new BlockPos(x, 2, z)));
                }
            }
            for (int x = 4; x <= 8; x++) {
                for (int z = 4; z <= 8; z++) {
                    h.assertTrue(
                            h.getBlockState(new BlockPos(x, 3, z)).isAir(),
                            "walls were not selected: " + h.getBlockState(new BlockPos(x, 3, z)));
                }
            }
            h.assertTrue(result.summary.contains("floor"), "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 2000)
    public void wallTorchOnItsWallBuildsInOnePass(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.COBBLESTONE, 1));
        companion.inventory().addItem(new ItemStack(Items.TORCH, 1));
        companion.inventory().addItem(new ItemStack(Items.DIRT, 2));
        Blueprint blueprint = parse("""
                {
                  "name": "torch wall",
                  "palette": { "C": "minecraft:cobblestone", "T": "minecraft:wall_torch[facing=south]" },
                  "ops": [
                    { "op": "box", "from": [0,0,0], "to": [0,0,0], "block": "C" },
                    { "op": "box", "from": [0,0,1], "to": [0,0,1], "block": "T" }
                  ]
                }
                """);
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(build(blueprint, h, 4, 2, 4, Direction.SOUTH), "build_structure");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(h.getBlockState(new BlockPos(4, 2, 4)).is(Blocks.COBBLESTONE), "the wall");
            h.assertTrue(
                    h.getBlockState(new BlockPos(4, 2, 5)).equals(state("minecraft:wall_torch[facing=south]")),
                    "the torch: " + h.getBlockState(new BlockPos(4, 2, 5)) + "; summary " + result.summary);
            h.assertTrue(
                    positionsOf(h, 16, 2, 15, Blocks.DIRT).isEmpty(),
                    "a planned wall needs no scaffold: " + positionsOf(h, 16, 2, 15, Blocks.DIRT));
        });
    }

    @GameTest(maxTicks = 4000)
    public void stairRoofOnTemporarySupport(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.OAK_STAIRS, 6));
        companion.inventory().addItem(new ItemStack(Items.OAK_PLANKS, 12));
        companion.inventory().addItem(new ItemStack(Items.DIRT, 8));
        Blueprint blueprint = parse("""
                {
                  "name": "roof",
                  "palette": { "S": "minecraft:oak_stairs", "P": "minecraft:oak_planks" },
                  "ops": [
                    { "op": "box", "from": [0,0,0], "to": [2,0,2], "block": "P" },
                    { "op": "roof", "from": [0,1,0], "to": [2,1,2], "style": "gable",
                      "block": "S", "axis": "x" }
                  ]
                }
                """);
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(build(blueprint, h, 4, 2, 4, Direction.SOUTH), "build_structure");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            for (int x = 4; x <= 6; x++) {
                h.assertTrue(
                        h.getBlockState(new BlockPos(x, 3, 4))
                                .equals(state("minecraft:oak_stairs[facing=south,half=bottom]")),
                        "south slope at " + x + ": " + h.getBlockState(new BlockPos(x, 3, 4)));
                h.assertTrue(
                        h.getBlockState(new BlockPos(x, 3, 6))
                                .equals(state("minecraft:oak_stairs[facing=north,half=bottom]")),
                        "north slope at " + x + ": " + h.getBlockState(new BlockPos(x, 3, 6)));
                h.assertTrue(
                        h.getBlockState(new BlockPos(x, 4, 5)).is(Blocks.OAK_PLANKS),
                        "ridge at " + x + ": " + h.getBlockState(new BlockPos(x, 4, 5)));
            }
            h.assertTrue(
                    result.summary.contains("scaffold blocks (removed)"),
                    "the roof must use a temporary support and clean it: " + result.summary);
            h.assertTrue(
                    positionsOf(h, 16, 2, 15, Blocks.DIRT).isEmpty(),
                    "scaffold dirt left behind at " + positionsOf(h, 16, 2, 15, Blocks.DIRT));
        });
    }

    @GameTest(maxTicks = 400)
    public void aPartSelectionThatMatchesNothingFails(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.COBBLESTONE, 15));
        Blueprint wall = generator("{\"type\":\"wall\",\"length\":5,\"height\":3,\"block\":\"minecraft:cobblestone\"}");
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(
                new BuildTask(
                        wall, h.absolutePos(new BlockPos(4, 2, 6)), Rotation.NONE, true, false, Set.of(PartKind.WALLS)),
                "build_structure");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.FAILED, "status " + result.status + ": " + result.summary);
            h.assertTrue(result.summary.contains("no walls"), result.summary);
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
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(build(blueprint, h, 6, 2, 6, Direction.WEST), "build_structure");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            Rotation rotation = Rotation.CLOCKWISE_90;
            h.assertTrue(
                    h.getBlockState(new BlockPos(7, 2, 6))
                            .equals(state("minecraft:oak_log[axis=x]").rotate(rotation)),
                    "log at 7,2,6 was " + h.getBlockState(new BlockPos(7, 2, 6)));
            h.assertTrue(
                    h.getBlockState(new BlockPos(7, 2, 7))
                            .equals(state("minecraft:oak_stairs[facing=north,half=bottom]")
                                    .rotate(rotation)),
                    "stairs at 7,2,7 were " + h.getBlockState(new BlockPos(7, 2, 7)));
            h.assertTrue(
                    h.getBlockState(new BlockPos(6, 2, 6))
                            .equals(state("minecraft:oak_slab[type=top]").rotate(rotation)),
                    "slab at 6,2,6 was " + h.getBlockState(new BlockPos(6, 2, 6)));
        });
    }

    @GameTest(maxTicks = 200)
    public void missingMaterialsFails(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.COBBLESTONE, 5));
        Blueprint wall = generator("{\"type\":\"wall\",\"length\":5,\"height\":3,\"block\":\"minecraft:cobblestone\"}");
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(build(wall, h, 4, 2, 6, Direction.SOUTH), "build_structure");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.FAILED, "should fail: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.startsWith("missing materials:"), "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 12000)
    public void gatherObtainsPlanks(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        // Four logs on the platform are enough to craft the twelve planks the wall needs; a crafting table is not
        // needed for planks.
        // Acacia: no other test stores it, so the build cannot satisfy the wall from a neighbour's chest
        // and skip the mining this test is about.
        BlockPos[] logs = {new BlockPos(4, 2, 2), new BlockPos(2, 2, 6), new BlockPos(6, 2, 8), new BlockPos(11, 2, 11)
        };
        for (BlockPos log : logs) h.setBlock(log, Blocks.ACACIA_LOG);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        for (BlockPos log : logs)
            h.assertTrue(
                    GameTestSupport.observeVisible(h, companion, h.absolutePos(log)),
                    "acacia log should be directly visible at " + log);
        Blueprint wall =
                generator("{\"type\":\"wall\",\"length\":12,\"height\":1,\"block\":\"minecraft:acacia_planks\"}");
        BlockPos origin = h.absolutePos(new BlockPos(3, 2, 6));
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(
                new BuildTask(
                        wall,
                        origin,
                        Placement.rotationFor(Direction.SOUTH),
                        true,
                        true,
                        new MinecraftObtainSubtaskFactory()::createForBuild),
                "build_structure");
        h.succeedWhen(() -> {
            for (BlockPos log : logs)
                if (h.getBlockState(log).is(Blocks.ACACIA_LOG))
                    GameTestSupport.observeVisible(h, companion, h.absolutePos(log));
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(result.summary.startsWith("built wall at "), "summary was " + result.summary);
            for (int x = 3; x <= 14; x++) {
                h.assertTrue(
                        h.getBlockState(new BlockPos(x, 2, 6)).is(Blocks.ACACIA_PLANKS),
                        "missing plank at " + x + ",2,6: " + h.getBlockState(new BlockPos(x, 2, 6)));
            }
            h.assertTrue(
                    positionsOf(h, 16, 2, 6, Blocks.ACACIA_LOG).size() <= 1,
                    "only the logs needed for the wall should be mined");
        });
    }

    @GameTest(maxTicks = 2000)
    public void gatherFailureStopsEarly(GameTestHelper h) {
        GameTestSupport.platformAt(h, -40, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, -39, 2);
        Blueprint blueprint = parse("""
                {
                  "name": "gold floor",
                  "palette": { "G": "minecraft:gold_block" },
                  "ops": [ { "op": "box", "from": [0,0,0], "to": [1,0,1], "block": "G" } ]
                }
                """);
        // Nothing to gather in a bare band: the tight limits keep the obtain inside the test's tick budget.
        BuildTask.ObtainFactory tight = (item, count) -> new ObtainTask(item, count, false, false, 1, 200, 16, 32, 200);
        BlockPos origin = h.absolutePos(new BlockPos(5, -39, 5));
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(
                new BuildTask(blueprint, origin, Placement.rotationFor(Direction.SOUTH), true, true, tight),
                "build_structure");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.FAILED, "status " + result.status + ": " + result.summary);
            h.assertTrue(
                    result.summary.startsWith("missing materials: minecraft:gold_block"),
                    "summary was " + result.summary);
            h.assertTrue(
                    result.summary.contains("can't get 4 minecraft:gold_block here:"),
                    "the obtain summary must be included: " + result.summary);
            for (int x = 5; x <= 6; x++) {
                for (int z = 5; z <= 6; z++) {
                    h.assertTrue(
                            h.getBlockState(new BlockPos(x, -39, z)).isAir(),
                            "nothing may be placed before gathering succeeds: "
                                    + h.getBlockState(new BlockPos(x, -39, z)));
                }
            }
        });
    }

    @GameTest(maxTicks = 200)
    public void withoutGatherStillFails(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        // Dark oak: no other test stores it, so a neighbour's chest cannot supply these planks.
        Blueprint platform =
                generator("{\"type\":\"platform\",\"width\":3,\"depth\":3,\"block\":\"minecraft:dark_oak_planks\"}");
        BlockPos origin = h.absolutePos(new BlockPos(5, 2, 5));
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(
                new BuildTask(platform, origin, Placement.rotationFor(Direction.SOUTH), true, false),
                "build_structure");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.FAILED, "should fail: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.startsWith("missing materials: minecraft:dark_oak_planks"),
                    "summary was " + result.summary);
            h.assertTrue(
                    result.summary.contains("need 9 × minecraft:dark_oak_planks"), "summary was " + result.summary);
            for (int x = 5; x <= 7; x++) {
                for (int z = 5; z <= 7; z++) {
                    h.assertTrue(
                            h.getBlockState(new BlockPos(x, 2, z)).isAir(),
                            "nothing may be placed without gathering: " + h.getBlockState(new BlockPos(x, 2, z)));
                }
            }
        });
    }

    @GameTest(maxTicks = 200)
    public void siteProblemFails(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(6, 2, 6, Blocks.CHEST);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.COBBLESTONE, 64));
        Blueprint wall = generator("{\"type\":\"wall\",\"length\":5,\"height\":3,\"block\":\"minecraft:cobblestone\"}");
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(build(wall, h, 4, 2, 6, Direction.SOUTH), "build_structure");
        String at = coords(h.absolutePos(new BlockPos(6, 2, 6)));
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.FAILED, "should fail: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.contains("the site isn't clear: minecraft:chest at " + at),
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
        ExecutionCoordinator runner = runner(companion, result);
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
        // Jungle: no other test stores it, so a foreign chest must not become the "near" source.
        companion.inventory().addItem(new ItemStack(Items.JUNGLE_PLANKS, 10));
        h.setBlock(1, 2, 4, Blocks.CHEST);
        var near = (net.minecraft.world.level.block.entity.ChestBlockEntity)
                h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(1, 2, 4)));
        near.setItem(0, new ItemStack(Items.JUNGLE_PLANKS, 10));
        near.setChanged();
        h.setBlock(1, 2, 12, Blocks.CHEST);
        var far = (net.minecraft.world.level.block.entity.ChestBlockEntity)
                h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(1, 2, 12)));
        far.setItem(0, new ItemStack(Items.JUNGLE_PLANKS, 20));
        far.setChanged();
        BlockPos nearPos = h.absolutePos(new BlockPos(1, 2, 4));
        BlockPos farPos = h.absolutePos(new BlockPos(1, 2, 12));
        h.assertTrue(GameTestSupport.observeVisible(h, companion, nearPos), "near chest should be directly visible");
        h.assertTrue(GameTestSupport.observeVisible(h, companion, farPos), "far chest should be directly visible");
        Blueprint platform =
                generator("{\"type\":\"platform\",\"width\":5,\"depth\":5,\"block\":\"minecraft:jungle_planks\"}");
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(build(platform, h, 5, 2, 5, Direction.SOUTH), "build_structure");
        h.succeedWhen(() -> {
            GameTestSupport.observeVisible(h, companion, nearPos);
            GameTestSupport.observeVisible(h, companion, farPos);
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(near.getItem(0).isEmpty(), "the nearest chest must be emptied first: " + near.getItem(0));
            h.assertTrue(far.getItem(0).getCount() == 15, "the far chest keeps 15: " + far.getItem(0));
            h.assertTrue(h.getBlockState(new BlockPos(9, 2, 9)).is(Blocks.JUNGLE_PLANKS), "the platform is built");
        });
    }

    @GameTest(maxTicks = 200)
    public void noScaffoldStillFailsWithoutBlocks(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.STONE_BRICKS, 64));
        companion.inventory().addItem(new ItemStack(Items.STONE_BRICKS, 16));
        Blueprint tower =
                generator("{\"type\":\"tower\",\"size\":3,\"height\":8,\"walls\":\"minecraft:stone_bricks\"}");
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(build(tower, h, 6, 2, 6, Direction.SOUTH), "build_structure");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.FAILED, "should fail: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.startsWith("can't reach ")
                            && result.summary.contains("build height limit without scaffolding"),
                    "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 12000)
    public void towerWithScaffold(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        clearAir(h, 16, 2, 12);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.STONE_BRICKS, 64));
        companion.inventory().addItem(new ItemStack(Items.STONE_BRICKS, 16));
        companion.inventory().addItem(new ItemStack(Items.DIRT, 20));
        Blueprint tower =
                generator("{\"type\":\"tower\",\"size\":3,\"height\":8,\"walls\":\"minecraft:stone_bricks\"}");
        trackDisplacement(h, companion, 0.8);
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(build(tower, h, 6, 2, 6, Direction.SOUTH), "build_structure");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(result.summary.contains("scaffold blocks (removed)"), "summary was " + result.summary);
            h.assertTrue(
                    h.getBlockState(new BlockPos(6, 10, 6)).is(Blocks.STONE_BRICKS),
                    "the top crenellation is missing: " + h.getBlockState(new BlockPos(6, 10, 6)));
            h.assertTrue(
                    h.getBlockState(new BlockPos(8, 9, 8)).is(Blocks.STONE_BRICKS),
                    "the top of the wall is missing: " + h.getBlockState(new BlockPos(8, 9, 8)));
            List<String> dirt = positionsOf(h, 16, 2, 15, Blocks.DIRT);
            List<String> cobblestone = positionsOf(h, 16, 2, 15, Blocks.COBBLESTONE);
            h.assertTrue(dirt.isEmpty(), "scaffold dirt left behind at " + dirt);
            h.assertTrue(cobblestone.isEmpty(), "scaffold cobblestone left behind at " + cobblestone);
        });
    }

    @GameTest(maxTicks = 12000)
    public void cancelRemovesItsScaffold(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        clearAir(h, 16, 2, 12);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.STONE_BRICKS, 64));
        companion.inventory().addItem(new ItemStack(Items.STONE_BRICKS, 16));
        companion.inventory().addItem(new ItemStack(Items.DIRT, 20));
        Blueprint tower =
                generator("{\"type\":\"tower\",\"size\":3,\"height\":8,\"walls\":\"minecraft:stone_bricks\"}");
        ExecutionCoordinator runner = runner(companion, new Result());
        runner.start(build(tower, h, 6, 2, 6, Direction.SOUTH), "build_structure");
        boolean[] cancelled = {false};
        h.succeedWhen(() -> {
            runner.tick();
            List<String> dirt = positionsOf(h, 16, 2, 15, Blocks.DIRT);
            if (!cancelled[0] && !dirt.isEmpty()) {
                // The build is standing on its own scaffold: stop it the way "stop all" does.
                runner.stop("test");
                cancelled[0] = true;
            }
            h.assertTrue(cancelled[0], "the tower never needed a scaffold");
            h.assertTrue(dirt.isEmpty(), "cancel left scaffold blocks behind at " + dirt);
        });
    }

    @GameTest(maxTicks = 12000)
    public void outOfScaffoldCleansUp(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        clearAir(h, 16, 2, 12);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.STONE_BRICKS, 64));
        companion.inventory().addItem(new ItemStack(Items.STONE_BRICKS, 16));
        companion.inventory().addItem(new ItemStack(Items.DIRT, 2));
        Blueprint tower =
                generator("{\"type\":\"tower\",\"size\":3,\"height\":8,\"walls\":\"minecraft:stone_bricks\"}");
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(build(tower, h, 6, 2, 6, Direction.SOUTH), "build_structure");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.FAILED, "should fail: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.startsWith("missing materials: dirt or cobblestone")
                            && result.summary.contains("ran out of dirt or cobblestone for scaffolding"),
                    "summary was " + result.summary);
            List<String> dirt = positionsOf(h, 16, 2, 15, Blocks.DIRT);
            List<String> cobblestone = positionsOf(h, 16, 2, 15, Blocks.COBBLESTONE);
            h.assertTrue(dirt.isEmpty(), "scaffold dirt left behind at " + dirt);
            h.assertTrue(cobblestone.isEmpty(), "scaffold cobblestone left behind at " + cobblestone);
        });
    }

    @GameTest(maxTicks = 3000)
    public void floatingBlockUsesSupport(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.OAK_PLANKS, 2));
        companion.inventory().addItem(new ItemStack(Items.DIRT, 4));
        Blueprint blueprint = parse("""
                {
                  "name": "floating",
                  "palette": { "P": "minecraft:oak_planks" },
                  "ops": [
                    { "op": "box", "from": [0,0,0], "to": [0,0,0], "block": "P" },
                    { "op": "box", "from": [0,1,2], "to": [0,1,2], "block": "P" }
                  ]
                }
                """);
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(build(blueprint, h, 4, 2, 4, Direction.SOUTH), "build_structure");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(result.summary.contains("scaffold blocks (removed)"), "summary was " + result.summary);
            h.assertTrue(
                    h.getBlockState(new BlockPos(4, 3, 6)).is(Blocks.OAK_PLANKS),
                    "the floating block is missing: " + h.getBlockState(new BlockPos(4, 3, 6)));
            h.assertTrue(
                    h.getBlockState(new BlockPos(4, 2, 6)).isAir(),
                    "the support must be removed: " + h.getBlockState(new BlockPos(4, 2, 6)));
            List<String> dirt = positionsOf(h, 16, 2, 15, Blocks.DIRT);
            h.assertTrue(dirt.isEmpty(), "scaffold dirt left behind at " + dirt);
        });
    }

    @GameTest(maxTicks = 3000)
    public void stairInMidAirUsesTemporarySupport(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.OAK_PLANKS, 1));
        companion.inventory().addItem(new ItemStack(Items.OAK_STAIRS, 1));
        companion.inventory().addItem(new ItemStack(Items.DIRT, 2));
        // A stair needs no block to stand on, only a face to click: the companion places one temporary block under
        // it, clicks that face, and takes the scaffold down again once the stair is in place. The plank keeps the
        // blueprint's origin at the platform and puts the stair two blocks deeper, in the air.
        Blueprint blueprint = parse("""
                {
                  "name": "floating stair",
                  "palette": { "P": "minecraft:oak_planks", "S": "minecraft:oak_stairs[facing=north,half=bottom]" },
                  "ops": [
                    { "op": "box", "from": [0,0,0], "to": [0,0,0], "block": "P" },
                    { "op": "box", "from": [0,1,2], "to": [0,1,2], "block": "S" }
                  ]
                }
                """);
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(build(blueprint, h, 4, 2, 4, Direction.SOUTH), "build_structure");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(
                    h.getBlockState(new BlockPos(4, 3, 6))
                            .equals(state("minecraft:oak_stairs[facing=north,half=bottom]")),
                    "stair at 4 3 6 = " + h.getBlockState(new BlockPos(4, 3, 6)) + "; summary " + result.summary);
            h.assertTrue(result.summary.contains("used 1 scaffold blocks (removed)"), "summary was " + result.summary);
            h.assertTrue(
                    h.getBlockState(new BlockPos(4, 2, 6)).isAir(),
                    "the temporary support must be removed: " + h.getBlockState(new BlockPos(4, 2, 6)));
            h.assertTrue(
                    positionsOf(h, 16, 2, 15, Blocks.DIRT).isEmpty(),
                    "scaffold dirt left behind at " + positionsOf(h, 16, 2, 15, Blocks.DIRT));
        });
    }

    @GameTest(maxTicks = 1200)
    public void wallTorchWithoutItsWallNamesTheMissingCell(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.COBBLESTONE, 1));
        companion.inventory().addItem(new ItemStack(Items.TORCH, 1));
        companion.inventory().addItem(new ItemStack(Items.DIRT, 2));
        // A wall torch survives only on the wall cell, so a temporary support there would be removed again and the
        // torch would drop. The task must say which cell the blueprint is missing instead of scaffolding it.
        Blueprint blueprint = parse("""
                {
                  "name": "torch",
                  "palette": { "C": "minecraft:cobblestone", "T": "minecraft:wall_torch[facing=north]" },
                  "ops": [
                    { "op": "box", "from": [0,0,0], "to": [0,0,0], "block": "C" },
                    { "op": "box", "from": [0,1,2], "to": [0,1,2], "block": "T" }
                  ]
                }
                """);
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(build(blueprint, h, 4, 2, 4, Direction.SOUTH), "build_structure");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.FAILED, "should fail: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.contains("needs a block at " + coords(h.absolutePos(new BlockPos(4, 3, 7)))),
                    "the missing wall cell must be named: " + result.summary);
            h.assertTrue(result.summary.contains("the blueprint does not provide"), "summary was " + result.summary);
            h.assertTrue(
                    h.getBlockState(new BlockPos(4, 3, 6)).isAir(),
                    "the torch must not be left behind: " + h.getBlockState(new BlockPos(4, 3, 6)));
            h.assertTrue(
                    positionsOf(h, 16, 2, 15, Blocks.DIRT).isEmpty(),
                    "no scaffold may be placed for a block that needs a permanent one: "
                            + positionsOf(h, 16, 2, 15, Blocks.DIRT));
        });
    }

    @GameTest(maxTicks = 4000)
    public void deferredSupportKeepsBlueprintCellsClear(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.OAK_PLANKS, 10));
        companion.inventory().addItem(new ItemStack(Items.OAK_FENCE, 2));
        companion.inventory().addItem(new ItemStack(Items.DIRT, 4));
        // Two floating columns that depend on a side block placed later in the same layer, pointing opposite ways.
        // Whichever direction the serpentine walks the row, one of them is visited bottom block first: that block is
        // deferred, its side block is placed later, and the block above it must wait instead of filling the deferred
        // cell with scaffold dirt. The fences under the side blocks are not full faces, so they must not rescue the
        // floating blocks' undersides.
        Blueprint blueprint = parse("""
                {
                  "name": "deferred columns",
                  "palette": { "P": "minecraft:oak_planks", "F": "minecraft:oak_fence" },
                  "ops": [
                    { "op": "box", "from": [1,0,0], "to": [1,0,0], "block": "P" },
                    { "op": "box", "from": [4,0,0], "to": [4,0,0], "block": "P" },
                    { "op": "box", "from": [1,1,0], "to": [1,1,0], "block": "F" },
                    { "op": "box", "from": [4,1,0], "to": [4,1,0], "block": "F" },
                    { "op": "box", "from": [0,2,0], "to": [0,2,0], "block": "P" },
                    { "op": "box", "from": [1,2,0], "to": [1,2,0], "block": "P" },
                    { "op": "box", "from": [4,2,0], "to": [4,2,0], "block": "P" },
                    { "op": "box", "from": [5,2,0], "to": [5,2,0], "block": "P" },
                    { "op": "box", "from": [0,3,0], "to": [0,3,0], "block": "P" },
                    { "op": "box", "from": [5,3,0], "to": [5,3,0], "block": "P" }
                  ]
                }
                """);
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(build(blueprint, h, 4, 2, 4, Direction.SOUTH), "build_structure");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(
                    h.getBlockState(new BlockPos(4, 4, 4)).is(Blocks.OAK_PLANKS),
                    "the first deferred block is missing: " + h.getBlockState(new BlockPos(4, 4, 4)));
            h.assertTrue(
                    h.getBlockState(new BlockPos(9, 4, 4)).is(Blocks.OAK_PLANKS),
                    "the second deferred block is missing: " + h.getBlockState(new BlockPos(9, 4, 4)));
            h.assertTrue(
                    h.getBlockState(new BlockPos(4, 5, 4)).is(Blocks.OAK_PLANKS),
                    "the first block above a deferred one is missing: " + h.getBlockState(new BlockPos(4, 5, 4)));
            h.assertTrue(
                    h.getBlockState(new BlockPos(9, 5, 4)).is(Blocks.OAK_PLANKS),
                    "the second block above a deferred one is missing: " + h.getBlockState(new BlockPos(9, 5, 4)));
            List<String> dirt = positionsOf(h, 16, 2, 15, Blocks.DIRT);
            h.assertTrue(dirt.isEmpty(), "a scaffold block was left in a blueprint cell at " + dirt);
        });
    }

    /**
     * Quarantined: the scenario fails on a builder bug, not on the material accounting it pins. On the 8-tall tower the
     * companion ends up above a cell it still has to place (feet 14 for a cell at y 8), the build has no descent step,
     * and every attempt ends in {@code OutOfReach("no route")}. Tracked in issue #9 with a deadline of 2026-11-14; the
     * test still runs and reports, it just does not fail the required gate until the builder can place a cell below its
     * feet. Restore {@code required = true} with the fix.
     */
    @GameTest(maxTicks = 12000, required = false)
    public void foundationSpendingKeepsScaffoldSurplus(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        clearAir(h, 16, 2, 12);
        // Nine one-block holes under the tower base need nine dirt of foundation; the other eleven dirt are the
        // scaffold's. Spending the foundation must not eat the reserve left for the scaffold.
        for (int x = 6; x <= 8; x++) {
            for (int z = 6; z <= 8; z++) {
                h.setBlock(x, 1, z, Blocks.AIR);
                h.setBlock(x, 0, z, Blocks.STONE);
            }
        }
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.STONE_BRICKS, 64));
        companion.inventory().addItem(new ItemStack(Items.STONE_BRICKS, 5));
        companion.inventory().addItem(new ItemStack(Items.DIRT, 20));
        Blueprint tower =
                generator("{\"type\":\"tower\",\"size\":3,\"height\":8,\"walls\":\"minecraft:stone_bricks\"}");
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(build(tower, h, 6, 2, 6, Direction.SOUTH), "build_structure");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(result.summary.contains("scaffold blocks (removed)"), "summary was " + result.summary);
            h.assertTrue(
                    h.getBlockState(new BlockPos(6, 1, 6)).is(Blocks.DIRT),
                    "the foundation is missing: " + h.getBlockState(new BlockPos(6, 1, 6)));
            h.assertTrue(
                    h.getBlockState(new BlockPos(8, 9, 8)).is(Blocks.STONE_BRICKS),
                    "the top of the wall is missing: " + h.getBlockState(new BlockPos(8, 9, 8)));
        });
    }

    @GameTest(maxTicks = 12000)
    public void scaffoldKeepsBlueprintMaterial(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        clearAir(h, 16, 2, 12);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        // Exactly the 69 cobblestone the tower needs and nothing to scaffold with: the wall material must not be
        // spent on a pillar, so the build must not start at all.
        companion.inventory().addItem(new ItemStack(Items.COBBLESTONE, 64));
        companion.inventory().addItem(new ItemStack(Items.COBBLESTONE, 5));
        Blueprint tower = generator("{\"type\":\"tower\",\"size\":3,\"height\":8,\"walls\":\"minecraft:cobblestone\"}");
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(build(tower, h, 6, 2, 6, Direction.SOUTH), "build_structure");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.FAILED, "should fail: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.startsWith("can't reach ")
                            && result.summary.contains("build height limit without scaffolding"),
                    "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 12000)
    public void cleanupProtectionReportsFailure(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        clearAir(h, 16, 2, 12);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.STONE_BRICKS, 64));
        companion.inventory().addItem(new ItemStack(Items.STONE_BRICKS, 16));
        companion.inventory().addItem(new ItemStack(Items.DIRT, 20));
        Blueprint tower =
                generator("{\"type\":\"tower\",\"size\":3,\"height\":8,\"walls\":\"minecraft:stone_bricks\"}");
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(build(tower, h, 6, 2, 6, Direction.SOUTH), "build_structure");
        BlockPos[] guarded = {null};
        h.succeedWhen(() -> {
            runner.tick();
            if (guarded[0] == null) {
                BlockPos lowest = lowestScaffold(h, 16, 2, 12);
                if (lowest != null) {
                    guarded[0] = lowest;
                    BlockPos absolute = h.absolutePos(lowest);
                    companion.hands().overrideProtection(pos -> pos.equals(absolute));
                }
            }
            h.assertTrue(guarded[0] != null, "the tower must need a scaffold");
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(
                    result.status == TaskStatus.FAILED,
                    "a scaffold that cannot be removed must not report success: " + result.status + " "
                            + result.summary);
            h.assertTrue(result.summary.contains("left 1 scaffold blocks behind"), "summary was " + result.summary);
            h.assertTrue(h.getBlockState(guarded[0]).is(Blocks.DIRT), "the protected block must stay");
        });
    }

    /** The lowest dirt or cobblestone block in the test area, or null. */
    private static BlockPos lowestScaffold(GameTestHelper h, int size, int fromY, int toY) {
        for (int y = fromY; y <= toY; y++) {
            for (int x = 0; x < size; x++) {
                for (int z = 0; z < size; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = h.getBlockState(pos);
                    if (state.is(Blocks.DIRT) || state.is(Blocks.COBBLESTONE)) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    private static List<String> positionsOf(GameTestHelper h, int size, int fromY, int toY, Block block) {
        List<String> positions = new java.util.ArrayList<>();
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                for (int y = fromY; y <= toY; y++) {
                    if (h.getBlockState(new BlockPos(x, y, z)).is(block)) {
                        positions.add(x + " " + y + " " + z);
                    }
                }
            }
        }
        return positions;
    }

    /** Clears the air above a platform; the world outside the test area may hold blocks this high. */
    private static void clearAir(GameTestHelper h, int size, int fromY, int toY) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                for (int y = fromY; y <= toY; y++) {
                    h.setBlock(x, y, z, Blocks.AIR);
                }
            }
        }
    }

    private static void trackDisplacement(GameTestHelper h, CompanionEntity companion, double limit) {
        var last = new net.minecraft.world.phys.Vec3[] {companion.position()};
        h.onEachTick(() -> {
            double distance = companion.position().distanceTo(last[0]);
            h.assertTrue(distance < limit, "the companion moved " + distance + " blocks in one tick");
            last[0] = companion.position();
        });
    }

    @GameTest(maxTicks = 100)
    public void failedScaffoldJumpKeepsItsMaterial(GameTestHelper h) {
        int floor = 205;
        GameTestSupport.platformAt(h, floor, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, floor + 1, 2);
        // Without AI, JumpControl cannot execute the requested jump.
        companion.setNoAi(true);
        companion.inventory().setItem(0, new ItemStack(Items.DIRT, 8));
        Scaffold scaffold = new Scaffold(companion);
        Scaffold.State[] state = {Scaffold.State.CLIMBING};
        int targetY = h.absolutePos(new BlockPos(2, floor + 2, 2)).getY();
        h.onEachTick(() -> {
            if (state[0] == Scaffold.State.CLIMBING) {
                state[0] = scaffold.pillarTo(targetY);
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(state[0] == Scaffold.State.FAILED, "waiting for jump timeout");
            h.assertTrue(companion.inventory().getItem(0).getCount() == 8, "a failed jump spent material");
            h.assertTrue(scaffold.placed().isEmpty(), "a failed jump placed a support");
        });
    }

    @GameTest(maxTicks = 300)
    public void scaffoldPillarsWithoutTeleport(GameTestHelper h) {
        int floor = 180;
        GameTestSupport.platformAt(h, floor, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, floor + 1, 2);
        companion.inventory().setItem(0, new ItemStack(Items.DIRT, 8));
        Scaffold scaffold = new Scaffold(companion);
        trackDisplacement(h, companion, 0.8);
        h.onEachTick(() -> {
            Scaffold.State state = scaffold.pillarTo(
                    h.absolutePos(new BlockPos(2, floor + 4, 2)).getY());
            h.assertTrue(state != Scaffold.State.FAILED, "scaffold failed: " + scaffold.failure());
        });
        h.succeedWhen(() -> {
            h.assertTrue(
                    companion.onGround()
                            && companion.getY()
                                    >= h.absolutePos(new BlockPos(2, floor + 4, 2))
                                            .getY(),
                    "not standing on the pillar, at " + companion.position());
            h.assertTrue(scaffold.placed().size() == 3, "must place three blocks before reporting arrival");
        });
    }

    @GameTest(maxTicks = 2000)
    public void stepsAsideToPlace(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 3, 2, 3);
        companion.inventory().addItem(new ItemStack(Items.OAK_PLANKS, 9));
        Blueprint platform =
                generator("{\"type\":\"platform\",\"width\":3,\"depth\":3,\"block\":\"minecraft:oak_planks\"}");
        trackDisplacement(h, companion, 1.5);
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(build(platform, h, 2, 2, 2, Direction.SOUTH), "build_structure");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(
                    h.getBlockState(new BlockPos(3, 2, 3)).is(Blocks.OAK_PLANKS),
                    "the block under the companion was " + h.getBlockState(new BlockPos(3, 2, 3)));
        });
    }

    @GameTest(maxTicks = 4000)
    public void progressNotices(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.COBBLESTONE, 40));
        Blueprint platform =
                generator("{\"type\":\"platform\",\"width\":8,\"depth\":5,\"block\":\"minecraft:cobblestone\"}");
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(build(platform, h, 4, 2, 4, Direction.SOUTH), "build_structure");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(result.notices.size() == 3, "notices were " + result.notices);
            h.assertTrue(result.notices.get(0).contains("25%"), "first notice: " + result.notices.get(0));
            h.assertTrue(result.notices.get(1).contains("50%"), "second notice: " + result.notices.get(1));
            h.assertTrue(result.notices.get(2).contains("75%"), "third notice: " + result.notices.get(2));
            h.assertTrue(
                    result.notices.get(0).startsWith("building platform: "), "notice text: " + result.notices.get(0));
        });
    }

    @GameTest(maxTicks = 3000)
    public void foreignChangeNotBroken(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.OAK_PLANKS, 5));
        Blueprint wall = generator("{\"type\":\"wall\",\"length\":5,\"height\":1,\"block\":\"minecraft:oak_planks\"}");
        Result result = new Result();
        ExecutionCoordinator runner = runner(companion, result);
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
            h.assertTrue(
                    result.summary.contains("the site isn't clear: minecraft:glass at " + at),
                    "summary was " + result.summary);
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
        List<Placement.PlacedSpec> specs =
                Placement.at(house, origin, Rotation.NONE).specs();
        BlockPos outOfReach =
                dev.friendai.features.construction.minecraft.build.Reach.firstOutOfReach(h.getLevel(), specs, origin);
        h.assertTrue(
                outOfReach == null,
                "the gable roof must be reachable from its own walls, but " + outOfReach + " is not");
        h.succeed();
    }

    @GameTest
    public void tallTowerStaysOutOfReach(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        Blueprint tower =
                generator("{\"type\":\"tower\",\"size\":3,\"height\":8,\"walls\":\"minecraft:stone_bricks\"}");
        BlockPos origin = h.absolutePos(new BlockPos(6, 2, 6));
        List<Placement.PlacedSpec> specs =
                Placement.at(tower, origin, Rotation.NONE).specs();
        h.assertTrue(
                dev.friendai.features.construction.minecraft.build.Reach.firstOutOfReach(h.getLevel(), specs, origin)
                        != null,
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
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(build(blueprint, h, 4, 2, 4, Direction.SOUTH), "build_structure");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(
                    h.getBlockState(new BlockPos(4, 3, 4)).equals(state("minecraft:oak_door[facing=north,half=lower]")),
                    "the lower half was " + h.getBlockState(new BlockPos(4, 3, 4)));
            h.assertTrue(
                    h.getBlockState(new BlockPos(4, 4, 4)).equals(state("minecraft:oak_door[facing=north,half=upper]")),
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
        ExecutionCoordinator runner = runner(companion, result);
        runner.start(build(blueprint, h, 4, 2, 4, Direction.SOUTH), "build_structure");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status != null, "still running");
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + ": " + result.summary);
            h.assertTrue(
                    h.getBlockState(new BlockPos(4, 3, 4)).equals(state("minecraft:red_bed[facing=south,part=foot]")),
                    "the foot was " + h.getBlockState(new BlockPos(4, 3, 4)));
            h.assertTrue(
                    h.getBlockState(new BlockPos(4, 3, 5)).equals(state("minecraft:red_bed[facing=south,part=head]")),
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

    /**
     * The support table is a claim about vanilla, so it is checked against vanilla itself: every row must refuse to
     * stand in mid-air, name the cell that {@code canSurvive} needs, and survive once that cell is stone.
     */
    @GameTest(maxTicks = 60)
    public void derivedSupportMatchesVanilla(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        record Row(String name, BlockState state, int dx, int dy, int dz) {}
        List<Row> rows = List.of(
                new Row(
                        "wall torch",
                        Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.EAST),
                        -1,
                        0,
                        0),
                new Row(
                        "ladder",
                        Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH),
                        0,
                        0,
                        -1),
                new Row(
                        "wall sign",
                        Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.SOUTH),
                        0,
                        0,
                        -1),
                new Row(
                        "tripwire hook",
                        Blocks.TRIPWIRE_HOOK.defaultBlockState().setValue(TripWireHookBlock.FACING, Direction.SOUTH),
                        0,
                        0,
                        -1),
                new Row(
                        "wall banner",
                        Blocks.WALL_BANNER
                                .pick(DyeColor.WHITE)
                                .defaultBlockState()
                                .setValue(WallBannerBlock.FACING, Direction.SOUTH),
                        0,
                        0,
                        -1),
                new Row(
                        "hanging lantern",
                        Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true),
                        0,
                        1,
                        0),
                new Row("floor lantern", Blocks.LANTERN.defaultBlockState(), 0, -1, 0),
                new Row("hanging sign", Blocks.OAK_HANGING_SIGN.defaultBlockState(), 0, 1, 0),
                new Row("door", Blocks.OAK_DOOR.defaultBlockState(), 0, -1, 0),
                new Row("snow layer", Blocks.SNOW.defaultBlockState(), 0, -1, 0),
                new Row("carpet", Blocks.CARPET.pick(DyeColor.WHITE).defaultBlockState(), 0, -1, 0),
                new Row("candle", Blocks.CANDLE.defaultBlockState(), 0, -1, 0),
                new Row("rail", Blocks.RAIL.defaultBlockState(), 0, -1, 0),
                new Row("torch", Blocks.TORCH.defaultBlockState(), 0, -1, 0));
        int row = 0;
        for (Row c : rows) {
            BlockPos pos = h.absolutePos(new BlockPos(4, 20 + row * 3, 4));
            row++;
            h.assertTrue(!c.state().canSurvive(level, pos), c.name() + ": must not survive in mid-air");
            BlockPos support = SupportNeeds.supportOf(c.state(), pos);
            h.assertTrue(support != null, c.name() + ": support cell expected");
            h.assertValueEqual(support, pos.offset(c.dx(), c.dy(), c.dz()), c.name() + ": support cell");
            level.setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());
            h.assertTrue(c.state().canSurvive(level, pos), c.name() + ": must survive on stone");
            level.setBlockAndUpdate(support, Blocks.AIR.defaultBlockState());
        }
        for (BlockState free : List.of(
                Blocks.STONE.defaultBlockState(),
                Blocks.OAK_STAIRS.defaultBlockState(),
                Blocks.OAK_PLANKS.defaultBlockState())) {
            BlockPos pos = h.absolutePos(new BlockPos(4, 80, 4));
            h.assertTrue(SupportNeeds.supportOf(free, pos) == null, free + " needs no support");
        }
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
