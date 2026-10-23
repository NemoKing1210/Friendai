package dev.friedai.test;

import dev.friedai.entity.CompanionEntity;
import dev.friedai.entity.FriedaiEntities;
import dev.friedai.task.nav.Approach;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class InteractTests {
    @GameTest(maxTicks = 400)
    public void approachReachesBlock(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        BlockPos target = h.absolutePos(new BlockPos(10, 2, 2));
        Approach approach = new Approach(target);
        h.succeedWhen(() -> {
            Approach.State state = approach.tick(c);
            h.assertTrue(state == Approach.State.IN_REACH, "state was " + state);
            double distance = c.getEyePosition().distanceTo(Vec3.atCenterOf(target));
            h.assertTrue(distance <= Approach.REACH, "eye distance was " + distance);
        });
    }

    @GameTest(maxTicks = 400)
    public void approachReportsUnreachable(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        BlockPos targetRel = new BlockPos(10, 2, 10);
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    h.setBlock(targetRel.offset(dx, dy, dz), Blocks.TINTED_GLASS);
                }
            }
        }
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        BlockPos target = h.absolutePos(targetRel);
        Approach approach = new Approach(target);
        h.succeedWhen(() -> {
            Approach.State state = approach.tick(c);
            h.assertTrue(state == Approach.State.UNREACHABLE, "state was " + state);
        });
    }
}
