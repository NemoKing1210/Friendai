package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.perception.PerceptionBuilder;
import dev.friendai.task.TaskContext;
import dev.friendai.task.mining.BlockBreaker;
import dev.friendai.survival.HungerModel;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/** Hunger on the entity: exhaustion sources, starvation, no passive regen, texts and save data. */
public class HungerTests {
    private static void assertClose(GameTestHelper h, float actual, float expected, String what) {
        h.assertTrue(Math.abs(actual - expected) < 1e-4f, what + " was " + actual + ", expected " + expected);
    }

    @GameTest
    public void miningAddsExhaustion(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        TaskContext context = new TaskContext(c, h.getLevel());
        int broken = 0;
        for (int x = 0; x <= 4; x++) {
            for (int z = 1; z <= 3; z += 2) {
                BlockPos target = h.absolutePos(new BlockPos(x, 2, z));
                h.setBlock(x, 2, z, Blocks.SHORT_GRASS);
                BlockBreaker breaker = new BlockBreaker(target, state -> state.is(Blocks.SHORT_GRASS));
                BlockBreaker.State state;
                int guard = 0;
                do {
                    state = breaker.tick(context);
                    h.assertTrue(++guard < 200, "block " + target + " not broken, state " + state);
                } while (state != BlockBreaker.State.BROKEN);
                broken++;
            }
        }
        h.assertTrue(broken == 10, "broke " + broken + " blocks, expected 10");
        assertClose(h, c.hunger().exhaustion(), 0.05f, "exhaustion after 10 blocks");
        h.succeed();
    }

    @GameTest
    public void hitTakenAddsExhaustion(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.hurtServer(h.getLevel(), h.getLevel().damageSources().generic(), 1.0f);
        assertClose(h, c.hunger().exhaustion(), 0.1f, "exhaustion after taking a hit");
        h.succeed();
    }

    @GameTest(maxTicks = 240)
    public void starvationFollowsDifficulty(GameTestHelper h) {
        GameTestSupport.platform(h, 10);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.hunger().load(0, 0.0f, 0.0f);
        c.setHealth(5.0f);
        Difficulty difficulty = h.getLevel().getDifficulty();
        float expected = switch (difficulty) {
            case PEACEFUL, EASY -> 5.0f;
            case NORMAL, HARD -> 3.0f;
        };
        h.runAfterDelay(170, () -> {
            assertClose(h, c.getHealth(), expected, "health after 160 ticks on " + difficulty);
            h.succeed();
        });
    }

    @GameTest(maxTicks = 500)
    public void noPassiveRegenAnyMore(GameTestHelper h) {
        GameTestSupport.platform(h, 10);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setHealth(10.0f);
        c.hunger().load(10, 0.0f, 0.0f);
        Difficulty difficulty = h.getLevel().getDifficulty();
        h.runAfterDelay(400, () -> {
            if (difficulty == Difficulty.PEACEFUL) {
                // Peaceful refills the bar and then regenerates like vanilla, so the removed
                // phase-5 timer cannot be told apart from the hunger model here.
                h.succeed();
                return;
            }
            h.assertTrue(c.getHealth() == 10.0f,
                    "health healed without food on " + difficulty + ": " + c.getHealth());
            h.succeed();
        });
    }

    @GameTest
    public void hungerInPerception(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.hunger().load(14, 0.0f, 0.0f);
        String text = PerceptionBuilder.brief(c).toPromptText();
        h.assertTrue(text.contains("Health: 20/20\nHunger: 14/20\n"), text);
        h.succeed();
    }

    @GameTest
    public void hungerSurvivesSave(GameTestHelper h) {
        CompanionEntity a = h.spawn(FriendaiEntities.COMPANION, 1, 2, 1);
        a.hunger().load(7, 2.5f, 1.5f);
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        a.saveWithoutId(out);

        CompanionEntity b = h.spawn(FriendaiEntities.COMPANION, 3, 2, 3);
        b.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), out.buildResult()));
        h.assertTrue(b.hunger().food() == 7, "food was " + b.hunger().food());
        assertClose(h, b.hunger().saturation(), 2.5f, "saturation");
        assertClose(h, b.hunger().exhaustion(), 1.5f, "exhaustion");
        h.succeed();
    }

    @GameTest
    public void oldSaveLoadsFull(GameTestHelper h) {
        CompanionEntity a = h.spawn(FriendaiEntities.COMPANION, 1, 2, 1);
        a.hunger().load(3, 0.0f, 0.0f);
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        a.saveWithoutId(out);
        CompoundTag tag = out.buildResult();
        h.assertTrue(a.hunger().food() == 3, "the saved companion should be hungry");
        tag.remove("Food");
        tag.remove("Saturation");
        tag.remove("Exhaustion");

        CompanionEntity b = h.spawn(FriendaiEntities.COMPANION, 3, 2, 3);
        b.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), tag));
        h.assertTrue(b.hunger().food() == HungerModel.MAX_FOOD, "food was " + b.hunger().food());
        assertClose(h, b.hunger().saturation(), 5.0f, "saturation");
        assertClose(h, b.hunger().exhaustion(), 0.0f, "exhaustion");
        h.succeed();
    }
}
