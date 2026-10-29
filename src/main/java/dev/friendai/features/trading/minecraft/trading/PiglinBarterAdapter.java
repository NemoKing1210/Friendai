package dev.friendai.features.trading.minecraft.trading;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.mixin.PiglinAiInvoker;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Narrow vanilla interaction adapter. It never creates or predicts a barter reward. */
public final class PiglinBarterAdapter {
    private static final double MAX_DISTANCE_SQUARED = 16.0;

    private final BarterDropTracker drops;

    public PiglinBarterAdapter(BarterDropTracker drops) {
        this.drops = java.util.Objects.requireNonNull(drops);
    }

    public boolean canStart(CompanionEntity companion, UUID piglinId) {
        if (companion == null
                || piglinId == null
                || companion.level().isClientSide()
                || !(companion.level() instanceof ServerLevel level)
                || companion.ownerUuid() == null
                || !companion.owner().isPresent()) return false;
        Entity entity = level.getEntity(piglinId);
        if (!(entity instanceof Piglin piglin)
                || !piglin.isAlive()
                || piglin.isBaby()
                || piglin.level() != level
                || piglin.getTarget() != null
                || piglin.isDancing()
                || piglin.getMainHandItem().is(Items.GOLD_INGOT)
                || drops.hasAttemptFor(piglinId)) return false;
        if (!level.getEntitiesOfClass(
                        ItemEntity.class,
                        piglin.getBoundingBox().inflate(4.0),
                        item -> item.isAlive() && item.getItem().is(Items.GOLD_INGOT))
                .isEmpty()) return false;
        return companion.distanceToSqr(piglin) <= MAX_DISTANCE_SQUARED;
    }

    /** Payment during a pre-registered attempt; no second task may share that piglin's attribution window. */
    public boolean payOne(CompanionEntity companion, UUID piglinId, UUID attemptId) {
        if (!drops.ownsAttempt(attemptId, piglinId)) return false;
        if ((!canStart(companion, piglinId) && !canStartIgnoringOwnAttempt(companion, piglinId, attemptId))
                || countGold(companion) < 1) return false;
        Entity entity = companion.level().getEntity(piglinId);
        if (!(entity instanceof Piglin piglin)) return false;
        int before = countGold(companion);
        var removed = InventoryOps.remove(companion.inventory(), plainGoldMatcher(), 1);
        if (removed.isEmpty()) return false;
        ItemStack ingot = removed.getFirst();
        boolean accepted;
        try {
            var result = companion.hands().interact(piglin, ingot);
            accepted = result.consumesAction() && countGold(companion) == before - 1;
        } catch (RuntimeException failure) {
            InventoryOps.insertOrDrop(companion, ingot);
            return false;
        }
        if (!accepted) {
            InventoryOps.insertOrDrop(companion, ingot);
            return false;
        }
        // Complete the same vanilla response immediately. If this call fails, never refund accepted gold.
        if (piglin.getOffhandItem().is(Items.GOLD_INGOT)) {
            BarterOutputCapture.begin(attemptId, piglinId);
            try {
                PiglinAiInvoker.friendai$finishAdmiration((ServerLevel) companion.level(), piglin, true);
            } finally {
                BarterOutputCapture.end();
            }
        }
        return true;
    }

    private boolean canStartIgnoringOwnAttempt(CompanionEntity companion, UUID piglinId, UUID attemptId) {
        if (!drops.ownsAttempt(attemptId, piglinId)) return false;
        if (companion == null || companion.level().isClientSide() || !(companion.level() instanceof ServerLevel level))
            return false;
        Entity entity = level.getEntity(piglinId);
        return entity instanceof Piglin piglin
                && piglin.isAlive()
                && !piglin.isBaby()
                && piglin.level() == level
                && piglin.getTarget() == null
                && !piglin.isDancing()
                && !piglin.getMainHandItem().is(Items.GOLD_INGOT)
                && companion.ownerUuid() != null
                && companion.owner().isPresent()
                && level.getEntitiesOfClass(
                                ItemEntity.class,
                                piglin.getBoundingBox().inflate(4.0),
                                item -> item.isAlive() && item.getItem().is(Items.GOLD_INGOT))
                        .isEmpty()
                && companion.distanceToSqr(piglin) <= MAX_DISTANCE_SQUARED;
    }

    private static int countGold(CompanionEntity companion) {
        return InventoryOps.count(companion.inventory(), plainGoldMatcher());
    }

    private static ItemMatcher plainGoldMatcher() {
        return new ItemMatcher() {
            @Override
            public boolean test(ItemStack stack) {
                return plainGold(stack);
            }

            @Override
            public String label() {
                return "minecraft:gold_ingot (plain)";
            }
        };
    }

    private static boolean plainGold(ItemStack stack) {
        return !stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, new ItemStack(Items.GOLD_INGOT));
    }
}
