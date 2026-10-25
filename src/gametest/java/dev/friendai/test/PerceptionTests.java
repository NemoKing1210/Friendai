package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.perception.PerceptionBuilder;
import dev.friendai.perception.PerceptionSnapshot;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

public class PerceptionTests {
    @GameTest
    public void selfReportsWhatItStandsOn(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setOnGround(true);
        PerceptionSnapshot p = PerceptionBuilder.brief(c);
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
            PerceptionSnapshot p = PerceptionBuilder.brief(c);
            h.assertTrue(p.self().inWater(), p.toPromptText());
        });
    }

    @GameTest
    public void threatsAreNamedWithTarget(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        var zombie = h.spawn(net.minecraft.world.entity.EntityTypes.ZOMBIE, 6, 2, 2);
        zombie.setTarget(c);
        PerceptionSnapshot p = PerceptionBuilder.brief(c);
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
        PerceptionSnapshot p = PerceptionBuilder.brief(c);
        h.assertTrue(p.threats().size() == 3, "threats: " + p.toPromptText());
        h.succeed();
    }

    @GameTest
    public void landmarksFindStationsNearby(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        h.setBlock(6, 2, 2, Blocks.CRAFTING_TABLE);
        h.setBlock(2, 2, 6, Blocks.FURNACE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        PerceptionSnapshot p = PerceptionBuilder.brief(c);
        h.assertTrue(p.landmarks().stream().anyMatch(l -> l.block().equals("crafting_table")),
                p.toPromptText());
        h.assertTrue(p.landmarks().stream().anyMatch(l -> l.block().equals("furnace")),
                p.toPromptText());
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
        h.setBlock(2, 2, 1, Blocks.WATER);
        h.setBlock(3, 2, 1, Blocks.CRAFTING_TABLE);
        h.setBlock(4, 2, 1, Blocks.FURNACE);
        h.setBlock(5, 2, 1, Blocks.ANVIL);
        h.setBlock(6, 2, 1, Blocks.CHEST);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 5);
        PerceptionSnapshot p = PerceptionBuilder.brief(c);
        h.assertTrue(p.landmarks().size() == 5, "landmarks: " + p.toPromptText());
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
            PerceptionSnapshot p = PerceptionBuilder.brief(c);
            h.assertTrue(!p.self().seesSky(), p.toPromptText());
        });
    }
}
