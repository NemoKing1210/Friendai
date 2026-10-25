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
