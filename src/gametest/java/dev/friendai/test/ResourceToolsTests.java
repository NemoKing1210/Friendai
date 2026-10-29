package dev.friendai.test;

import com.google.gson.JsonObject;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.features.acquisition.minecraft.search.ResourceResolver;
import dev.friendai.features.knowledge.ResourceObservation;
import dev.friendai.features.memory.minecraft.tool.MemoryAccess;
import dev.friendai.features.navigation.pathing.Pos;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;

public final class ResourceToolsTests {
    @GameTest(maxTicks = 80)
    public void resourceResolverAndKnownResourceScope(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        var rawIron = ResourceResolver.resolve(h.getLevel(), "minecraft:raw_iron");
        h.assertTrue(rawIron.blockIds().contains("minecraft:iron_ore"), "raw iron should resolve to iron ore");
        h.assertTrue(
                rawIron.blockIds().contains("minecraft:deepslate_iron_ore"),
                "raw iron should resolve to deepslate iron ore");
        var ingot = ResourceResolver.resolve(h.getLevel(), "minecraft:iron_ingot");
        h.assertTrue(ingot.reason().contains("smelting"), "iron ingot should explain smelting source");

        var logs = ResourceResolver.resolve(h.getLevel(), "#minecraft:logs");
        h.assertTrue(logs.executable(), logs.reason());
        h.assertTrue(
                logs.blockIds().contains("minecraft:acacia_log"),
                logs.blockIds().toString());
        h.assertTrue(logs.family().equals("#minecraft:logs"), logs.family());
        var planks = ResourceResolver.resolve(h.getLevel(), "minecraft:oak_planks");
        h.assertTrue(planks.executable(), planks.reason());
        h.assertTrue(planks.family().equals("#minecraft:logs"), planks.family());

        var state = GameTestSupport.runtime(h.getLevel().getServer());
        BlockPos sitePos = companion.blockPosition().offset(3, 0, 4);
        var location = new ResourceObservation.Location(
                h.getLevel().dimension().identifier().toString(),
                new Pos(sitePos.getX(), sitePos.getY(), sitePos.getZ()));
        state.knowledge()
                .book(owner.getUUID())
                .observe(
                        new ResourceObservation(
                                "minecraft:iron_ore",
                                location,
                                new Pos(
                                        companion.blockPosition().getX(),
                                        companion.blockPosition().getY(),
                                        companion.blockPosition().getZ()),
                                h.getLevel().getServer().overworld().getGameTime()),
                        "minecraft:iron_ore");

        ToolRegistry<CompanionEntity> tools = GameTestToolRegistry.create(companion, () -> 16, MemoryAccess.DISABLED);
        var guest = tools.bind(companion)
                .execute(
                        call("minecraft:raw_iron"),
                        new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.GUEST));
        h.assertTrue(guest.error(), "guests must not query owner resource knowledge");
        var trusted = tools.bind(companion)
                .execute(
                        call("minecraft:raw_iron"),
                        new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.TRUSTED));
        h.assertTrue(
                !trusted.error() && trusted.content().contains("historical_count"),
                "trusted query should return site data");
        h.assertTrue(
                trusted.content().contains("distance_blocks")
                        && trusted.content().contains("5.0"),
                "query should report straight-line 3-4-5 distance");

        var unknown = tools.bind(companion)
                .execute(
                        call("friendai:no_such_resource"),
                        new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.TRUSTED));
        h.assertTrue(unknown.error(), "unknown resource ids should be a tool error");
        h.succeed();
    }

    @GameTest(maxTicks = 40)
    public void queryUnloadedSiteDoesNotLoadChunk(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        BlockPos far = new BlockPos(4096, 20, 4096);
        var level = h.getLevel();
        h.assertTrue(!level.hasChunkAt(far), "test location should start unloaded");
        var state = GameTestSupport.runtime(level.getServer());
        var location = new ResourceObservation.Location(
                level.dimension().identifier().toString(), new Pos(far.getX(), far.getY(), far.getZ()));
        state.knowledge()
                .book(owner.getUUID())
                .observe(
                        new ResourceObservation(
                                "minecraft:coal_ore",
                                location,
                                new Pos(0, 0, 0),
                                level.getServer().overworld().getGameTime()),
                        "minecraft:coal_ore");
        JsonObject args = new JsonObject();
        args.addProperty("resource", "minecraft:coal_ore");
        args.addProperty("max_distance", 30_000_000);
        var result = GameTestToolRegistry.create(companion, () -> 16, MemoryAccess.DISABLED)
                .bind(companion)
                .execute(
                        new ToolCall("test", "known_resources", args, args.toString(), null),
                        new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.TRUSTED));
        h.assertTrue(!result.error(), "known resource query should not require a loaded chunk");
        h.assertTrue(!level.hasChunkAt(far), "known resource query loaded an unloaded chunk");
        h.succeed();
    }

    @GameTest(maxTicks = 60)
    public void searchResourceAcceptsWoodTag(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        JsonObject args = new JsonObject();
        args.addProperty("resource", "#minecraft:logs");
        var result = GameTestToolRegistry.create(companion, () -> 16, MemoryAccess.DISABLED)
                .bind(companion)
                .execute(
                        new ToolCall("test", "search_resource", args, args.toString(), null),
                        new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));
        h.assertTrue(!result.error(), result.content());
        h.assertTrue(
                companion
                        .executionCoordinator()
                        .current()
                        .map(task -> task.type().equals("search_resource"))
                        .orElse(false),
                "a wood search should start: " + result.content());
        h.succeed();
    }

    @GameTest(maxTicks = 20)
    public void unresolvedKnownItemIsNotExecutable(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        var resolution = ResourceResolver.resolve(h.getLevel(), "minecraft:debug_stick");
        h.assertTrue(
                !resolution.executable(), "an item without a block source must not be executable as a resource visit");
        h.succeed();
    }

    private static ToolCall call(String resource) {
        JsonObject args = new JsonObject();
        args.addProperty("resource", resource);
        return new ToolCall("test", "known_resources", args, args.toString(), null);
    }
}
