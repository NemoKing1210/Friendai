package dev.friendai.server.companion;

import dev.friendai.entity.CompanionEntity;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;

/** Applies item pickup policy and transfers eligible drops into the companion inventory. */
public final class CompanionPickup {
    private CompanionPickup() {}

    public static int collect(CompanionEntity companion, double radius, Predicate<ItemEntity> filter) {
        int moved = 0;
        List<ItemEntity> items = companion
                .level()
                .getEntitiesOfClass(ItemEntity.class, companion.getBoundingBox().inflate(radius));
        for (ItemEntity item : items) {
            if (companion.canCollect(item) && filter.test(item)) moved += companion.storeItem(item);
        }
        return moved;
    }

    public static void collectOwnerThrows(CompanionEntity companion, double radius, UUID owner) {
        List<ItemEntity> items = companion
                .level()
                .getEntitiesOfClass(ItemEntity.class, companion.getBoundingBox().inflate(radius));
        for (ItemEntity item : items) {
            if (!item.isRemoved()
                    && item.getOwner() instanceof ServerPlayer player
                    && player.getUUID().equals(owner)) {
                companion.storeItem(item);
            }
        }
    }
}
