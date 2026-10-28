package dev.friendai.test;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.perception.PerceptionBuilder;
import dev.friendai.perception.PerceptionContext;
import dev.friendai.perception.PerceptionSnapshot;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

public class PerceptionTests {
    private static PerceptionSnapshot brief(CompanionEntity companion) {
        var state = FriendaiServerState.get(companion.level().getServer());
        return PerceptionBuilder.brief(
                companion, new PerceptionContext(state.resources(), state.settings(), state.freeTime()));
    }

    @GameTest
    public void selfReportsWhatItStandsOn(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setOnGround(true);
        PerceptionSnapshot p = brief(c);
        h.assertTrue(p.self() != null, "no self status");
        h.assertTrue(p.self().underfoot().startsWith("stone at "), p.toPromptText());
        h.assertTrue(p.self().onGround(), p.toPromptText());
        h.assertTrue(p.self().lightLevel() >= 0 && p.self().lightLevel() <= 15, p.toPromptText());
        h.succeed();
    }

    @GameTest
    public void selfReportsAWaterBlockAtItsFeet(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        h.setBlock(2, 2, 2, Blocks.WATER);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        // isInWater() reads the fluid state cached by the entity's tick, not the block grid.
        h.succeedWhen(() -> {
            PerceptionSnapshot p = brief(c);
            h.assertTrue(p.self().inWater(), p.toPromptText());
        });
    }

    @GameTest
    public void threatsAreNamedWithTarget(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        var zombie = h.spawn(net.minecraft.world.entity.EntityTypes.ZOMBIE, 6, 2, 2);
        zombie.setTarget(c);
        PerceptionSnapshot p = brief(c);
        h.assertTrue(p.threats().size() == 1, p.toPromptText());
        h.assertTrue(p.threats().get(0).type().equals("zombie"), p.toPromptText());
        h.assertTrue(p.threats().get(0).target().equals("me"), p.toPromptText());
        h.succeed();
    }

    @GameTest
    public void threatsAreCappedAtThree(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        for (int i = 0; i < 4; i++) {
            h.spawn(net.minecraft.world.entity.EntityTypes.ZOMBIE, 5 + i, 2, 5);
        }
        PerceptionSnapshot p = brief(c);
        h.assertTrue(p.threats().size() == 3, "threats: " + p.toPromptText());
        h.succeed();
    }

    @GameTest
    public void treesAreListedWithTheirKind(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        for (int y = 2; y <= 6; y++) {
            h.setBlock(5, y, 2, Blocks.CHERRY_LOG);
        }
        h.setBlock(
                5,
                7,
                2,
                Blocks.CHERRY_LEAVES
                        .defaultBlockState()
                        .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        for (int y = 2; y <= 6; y++) {
            h.assertTrue(
                    GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(5, y, 2))),
                    "tree log should be directly visible at y=" + y);
        }
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(5, 7, 2))),
                "tree leaves should be directly visible");
        PerceptionSnapshot p = brief(c);
        h.assertTrue(
                p.toPromptText().contains("Trees: minecraft:cherry_log 3 blocks east (5 logs, height 5, standing)"),
                p.toPromptText());
        h.succeed();
    }

    @GameTest
    public void landmarksFindStationsNearby(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        h.setBlock(6, 2, 2, Blocks.CRAFTING_TABLE);
        h.setBlock(2, 2, 6, Blocks.FURNACE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(6, 2, 2))),
                "crafting table should be directly visible");
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(2, 2, 6))),
                "furnace should be directly visible");
        PerceptionSnapshot p = brief(c);
        h.assertTrue(p.landmarks().stream().anyMatch(l -> l.block().equals("crafting_table")), p.toPromptText());
        h.assertTrue(p.landmarks().stream().anyMatch(l -> l.block().equals("furnace")), p.toPromptText());
        h.succeed();
    }

    @GameTest
    public void landmarksAtTheWorldFloorDoNotThrow(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        var list = dev.friendai.perception.LandmarkScanner.scan(
                h.getLevel(), new net.minecraft.core.BlockPos(0, h.getLevel().getMinY(), 0));
        h.assertTrue(list.size() <= 5, "landmarks: " + list.size());
        h.succeed();
    }

    @GameTest
    public void landmarksAreCappedAtFive(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        h.setBlock(1, 2, 1, Blocks.LAVA);
        h.setBlock(1, 2, 6, Blocks.WATER);
        h.setBlock(6, 2, 1, Blocks.CRAFTING_TABLE);
        h.setBlock(11, 2, 6, Blocks.FURNACE);
        h.setBlock(6, 2, 11, Blocks.ANVIL);
        h.setBlock(11, 2, 11, Blocks.CHEST);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 6, 2, 6);
        for (BlockPos landmark : List.of(
                new BlockPos(1, 2, 1),
                new BlockPos(1, 2, 6),
                new BlockPos(6, 2, 1),
                new BlockPos(11, 2, 6),
                new BlockPos(6, 2, 11),
                new BlockPos(11, 2, 11))) {
            h.assertTrue(
                    GameTestSupport.observeVisible(h, c, h.absolutePos(landmark)),
                    "landmark should be directly visible at " + landmark);
        }
        PerceptionSnapshot p = brief(c);
        h.assertTrue(p.landmarks().size() == 5, "landmarks: " + p.toPromptText());
        h.succeed();
    }

    @GameTest
    public void surveyAreaShowsSelfWallAndSpeaker(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        for (int x = 0; x < 8; x++) {
            h.setBlock(x, 2, 6, Blocks.STONE);
        }
        var owner = GameTestSupport.mockPlayerAt(h, 4.5, 2, 2.5);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setOwner(owner.getUUID());
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(2, 2, 6))),
                "wall should be directly visible");
        var tools = dev.friendai.tool.builtin.FriendaiTools.create(() -> 32).bind(c);
        var call =
                new dev.friendai.llm.model.ToolCall("1", "survey_area", new com.google.gson.JsonObject(), "{}", null);
        var result = tools.execute(
                call, new dev.friendai.tool.Caller(owner.getUUID(), "owner", dev.friendai.tool.AccessLevel.OWNER));
        h.assertTrue(!result.error(), result.content());
        h.assertTrue(result.content().contains("@"), result.content());
        h.assertTrue(result.content().contains("#"), result.content());
        h.assertTrue(result.content().contains("O"), result.content());
        h.succeed();
    }

    @GameTest
    public void selfReportsNoSkyUnderARoof(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                h.setBlock(x, 5, z, Blocks.STONE);
            }
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        // canSeeSky() reads propagated sky light, which the light engine settles after a tick.
        h.succeedWhen(() -> {
            PerceptionSnapshot p = brief(c);
            h.assertTrue(!p.self().seesSky(), p.toPromptText());
        });
    }
}
