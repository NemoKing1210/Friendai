package dev.friendai.test;

import com.google.gson.JsonObject;
import dev.friendai.build.Blueprint;
import dev.friendai.build.BlueprintParser;
import dev.friendai.build.ParseResult;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.task.build.BlueprintResolver;
import dev.friendai.task.build.BlueprintStore;
import dev.friendai.task.build.Placement;
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
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

public class BuildTests {
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
}
