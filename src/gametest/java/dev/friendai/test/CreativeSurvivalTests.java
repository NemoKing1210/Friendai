package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.features.navigation.pathing.BlockList;
import dev.friendai.task.TaskContext;
import dev.friendai.task.mining.BreakAction;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

public final class CreativeSurvivalTests {
    @GameTest
    public void creativeModeIgnoresOrdinaryAndEnvironmentalDamage(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setCreativeMode(true);
        float health = companion.getHealth();
        ServerLevel level = h.getLevel();

        h.assertFalse(companion.hurtServer(level, companion.damageSources().generic(), 4), "combat damage was applied");
        h.assertFalse(companion.hurtServer(level, companion.damageSources().inFire(), 4), "fire damage was applied");
        h.assertFalse(companion.hurtServer(level, companion.damageSources().drown(), 4), "drowning damage was applied");
        h.assertFalse(companion.hurtServer(level, companion.damageSources().fall(), 4), "fall damage was applied");
        h.assertTrue(companion.getHealth() == health, "creative damage changed health");
        h.assertFalse(
                companion.isInvulnerableTo(level, companion.damageSources().fellOutOfWorld()),
                "creative immunity must preserve the void damage exception");
        h.succeed();
    }

    @GameTest(maxTicks = 80)
    public void creativeModeFreezesHungerAndSkipsAutomaticFoodQuest(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setCreativeMode(true);
        companion.hunger().load(0, 0, 0);
        companion.hungerWatcher().setAutoDelayTicks(0);
        float health = companion.getHealth();
        companion.hungerWatcher().tick();
        h.assertFalse(
                companion
                        .executionCoordinator()
                        .current()
                        .map(task -> task.type().equals("get_food"))
                        .orElse(false),
                "creative hunger started an automatic food quest");

        h.runAfterDelay(40, () -> {
            h.assertTrue(companion.hunger().food() == 0, "creative hunger changed from zero");
            h.assertTrue(companion.getHealth() == health, "starvation damaged the companion in creative mode");
            h.assertFalse(
                    companion
                            .executionCoordinator()
                            .current()
                            .map(task -> task.type().equals("get_food"))
                            .orElse(false),
                    "creative hunger started an automatic food quest");
            h.succeed();
        });
    }

    @GameTest
    public void creativeHandsUseCreativeModeAndReturnTheHeldCopyOnce(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 3, 2, 3);
        companion.setCreativeMode(true);
        BlockPos against = h.absolutePos(new BlockPos(4, 1, 4));

        companion.hands().place(new ItemStack(Items.MOSSY_COBBLESTONE, 3), against, net.minecraft.core.Direction.UP);

        h.assertTrue(
                companion.hands().player().gameMode.getGameModeForPlayer()
                        == net.minecraft.world.level.GameType.CREATIVE,
                "fake player did not synchronize creative mode");
        h.assertTrue(
                companion.inventory().countItem(Items.MOSSY_COBBLESTONE) == 3,
                "creative placement must preserve the supplied stack exactly once");
        h.assertTrue(companion.hands().player().getInventory().isEmpty(), "fake-player inventory was not cleared");
        h.assertTrue(h.getBlockState(new BlockPos(4, 2, 4)).is(Blocks.MOSSY_COBBLESTONE), "block was not placed");
        h.succeed();
    }

    @GameTest
    public void survivalModeStillTakesDamageAndConsumesPlacementItem(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 3, 2, 3);
        float health = companion.getHealth();
        h.assertTrue(
                companion.hurtServer(h.getLevel(), companion.damageSources().generic(), 2),
                "survival damage was suppressed");
        h.assertTrue(companion.getHealth() < health, "survival damage did not change health");

        BlockPos against = h.absolutePos(new BlockPos(4, 1, 4));
        companion.hands().place(new ItemStack(Items.MOSSY_COBBLESTONE, 3), against, net.minecraft.core.Direction.UP);
        h.assertTrue(
                companion.hands().player().gameMode.getGameModeForPlayer()
                        == net.minecraft.world.level.GameType.SURVIVAL,
                "fake player did not return to survival mode");
        h.assertTrue(
                companion.inventory().countItem(Items.MOSSY_COBBLESTONE) == 2,
                "survival placement did not consume exactly one item");
        h.succeed();
    }

    @GameTest(maxTicks = 20)
    public void creativeHandBreaksBedrockInstantlyButHonorsProtection(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setCreativeMode(true);
        BlockPos target = h.absolutePos(new BlockPos(3, 2, 2));
        h.setBlock(new BlockPos(3, 2, 2), Blocks.BEDROCK);
        TaskContext context = new TaskContext(companion, h.getLevel());

        BreakAction protectedAction = new BreakAction(target);
        companion.hands().overrideProtection(pos -> pos.equals(target));
        h.assertTrue(
                protectedAction.tick(context, state -> state.is(Blocks.BEDROCK)) == BreakAction.State.BLOCKED,
                "creative mode bypassed block protection");
        h.assertTrue(h.getLevel().getBlockState(target).is(Blocks.BEDROCK), "protected bedrock was removed");

        companion.hands().overrideProtection(null);
        BreakAction action = new BreakAction(target);
        h.assertTrue(
                action.tick(context, state -> state.is(Blocks.BEDROCK)) == BreakAction.State.BROKEN,
                "creative hand did not instantly break bedrock");
        h.assertTrue(h.getLevel().getBlockState(target).isAir(), "bedrock remained after creative break");
        h.succeed();
    }

    @GameTest(maxTicks = 20)
    public void creativeBreakProducesNoNormalDropsOrExperience(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setCreativeMode(true);
        companion.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, Items.IRON_PICKAXE.getDefaultInstance());
        BlockPos target = h.absolutePos(new BlockPos(3, 2, 2));
        h.setBlock(new BlockPos(3, 2, 2), Blocks.DIAMOND_ORE);
        BreakAction action = new BreakAction(target);
        var current = companion.serverSettings();
        var testSettings = new dev.friendai.server.configuration.ServerSettings(
                current.tasks(),
                current.pathing(),
                BlockList.parse(java.util.List.of(), ignored -> {}),
                current.placeList(),
                current.autonomy(),
                current.trading());
        TaskContext context =
                new TaskContext(companion, h.getLevel(), companion.executionCoordinator(), null, testSettings);
        BreakAction.State state = action.tick(context, block -> block.is(Blocks.DIAMOND_ORE));
        h.assertTrue(state == BreakAction.State.BROKEN, "creative break was refused: " + state);
        AABB nearby = new AABB(target).inflate(3);
        h.assertTrue(
                h.getLevel().getEntitiesOfClass(ItemEntity.class, nearby).isEmpty(),
                "creative break spawned item drops");
        h.assertTrue(
                h.getLevel()
                        .getEntitiesOfClass(net.minecraft.world.entity.ExperienceOrb.class, nearby)
                        .isEmpty(),
                "creative break spawned experience");
        h.succeed();
    }

    @GameTest
    public void creativeBreakHonorsConfiguredBlacklist(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setCreativeMode(true);
        BlockPos target = h.absolutePos(new BlockPos(3, 2, 2));
        h.setBlock(new BlockPos(3, 2, 2), Blocks.DIAMOND_ORE);
        BreakAction action = new BreakAction(target);

        h.assertTrue(
                action.tick(new TaskContext(companion, h.getLevel()), state -> state.is(Blocks.DIAMOND_ORE))
                        == BreakAction.State.BLOCKED,
                "creative break bypassed the configured default ore blacklist");
        h.assertTrue(h.getLevel().getBlockState(target).is(Blocks.DIAMOND_ORE), "blacklisted ore was removed");
        h.succeed();
    }
}
