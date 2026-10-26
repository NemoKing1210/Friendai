package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

/** Experience on the entity: orbs are pulled in and absorbed, the level is synced and survives a save. */
public class ExperienceTests {
    private static final int BAND_Y = 280;

    private static ExperienceOrb spawnOrb(GameTestHelper h, double x, double z, int value) {
        Vec3 p = h.absoluteVec(new Vec3(x, BAND_Y + 1, z));
        ExperienceOrb orb = new ExperienceOrb(h.getLevel(), p.x, p.y, p.z, value);
        h.getLevel().addFreshEntity(orb);
        return orb;
    }

    private static boolean noOrbsNear(GameTestHelper h, CompanionEntity c, double radius) {
        List<ExperienceOrb> orbs = h.getLevel().getEntitiesOfClass(ExperienceOrb.class, c.getBoundingBox().inflate(radius));
        return orbs.isEmpty();
    }

    @GameTest(maxTicks = 200)
    public void absorbsOrbsNearby(GameTestHelper h) {
        GameTestSupport.platformAt(h, BAND_Y, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, BAND_Y + 1, 2);
        spawnOrb(h, 4, 2, 7);
        h.succeedWhen(() -> {
            h.assertTrue(noOrbsNear(h, c, 6), "the orb was not absorbed");
            h.assertTrue(c.experience().points() == 7, "points were " + c.experience().points());
            h.assertTrue(c.syncedXpLevel() == 1, "synced level was " + c.syncedXpLevel());
        });
    }

    @GameTest(maxTicks = 200)
    public void absorbsTwoOrbsOnce(GameTestHelper h) {
        GameTestSupport.platformAt(h, BAND_Y, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, BAND_Y + 1, 2);
        spawnOrb(h, 3, 2, 3);
        spawnOrb(h, 3, 2, 4);
        h.succeedWhen(() -> {
            h.assertTrue(noOrbsNear(h, c, 6), "an orb was left behind");
            h.assertTrue(c.experience().points() == 7, "points were " + c.experience().points());
        });
    }

    @GameTest(maxTicks = 100)
    public void ignoresFarOrbs(GameTestHelper h) {
        GameTestSupport.platformAt(h, BAND_Y + 20, 30);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, BAND_Y + 21, 2);
        Vec3 p = h.absoluteVec(new Vec3(20, BAND_Y + 21, 2));
        ExperienceOrb orb = new ExperienceOrb(h.getLevel(), p.x, p.y, p.z, 5);
        h.getLevel().addFreshEntity(orb);
        h.runAfterDelay(40, () -> {
            h.assertTrue(!orb.isRemoved(), "a far orb was absorbed");
            h.assertTrue(c.experience().points() == 0, "points were " + c.experience().points());
            h.succeed();
        });
    }

    @GameTest
    public void savesAndLoadsExperience(GameTestHelper h) {
        CompanionEntity a = h.spawn(FriendaiEntities.COMPANION, 1, 2, 1);
        a.addExperience(100);
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        a.saveWithoutId(out);

        CompanionEntity b = h.spawn(FriendaiEntities.COMPANION, 3, 2, 3);
        b.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), out.buildResult()));
        h.assertTrue(b.experience().points() == 100, "points were " + b.experience().points());
        h.assertTrue(b.syncedXpLevel() == b.experience().level(), "the synced level did not follow the load");
        h.succeed();
    }

    @GameTest
    public void oldSaveLoadsAsZero(GameTestHelper h) {
        CompanionEntity a = h.spawn(FriendaiEntities.COMPANION, 1, 2, 1);
        a.addExperience(100);
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        a.saveWithoutId(out);
        CompoundTag tag = out.buildResult();
        tag.remove("XpPoints");

        CompanionEntity b = h.spawn(FriendaiEntities.COMPANION, 3, 2, 3);
        b.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), tag));
        h.assertTrue(b.experience().points() == 0, "points were " + b.experience().points());
        h.succeed();
    }

    @GameTest
    public void corruptNegativeSaveClamps(GameTestHelper h) {
        CompanionEntity a = h.spawn(FriendaiEntities.COMPANION, 1, 2, 1);
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        a.saveWithoutId(out);
        CompoundTag tag = out.buildResult();
        tag.putInt("XpPoints", -5);

        CompanionEntity b = h.spawn(FriendaiEntities.COMPANION, 3, 2, 3);
        b.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), tag));
        h.assertTrue(b.experience().points() == 0, "points were " + b.experience().points());
        h.succeed();
    }
}
