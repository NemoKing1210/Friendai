package dev.friendai.task.trading;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.trading.TradeBook;
import dev.friendai.trading.TradeStore;
import dev.friendai.trading.TraderRecord;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.phys.AABB;

/** Read-only observations of loaded merchants near one companion; never opens a menu or loads a chunk. */
public final class TraderObserver {
    public static final int OBSERVATION_RADIUS = 64;

    private final TradeStore store;

    public TraderObserver(TradeStore store) {
        this.store = java.util.Objects.requireNonNull(store);
    }

    /** Refreshes locations for nearby loaded merchants while retaining previously inspected offer quotes. */
    public List<TraderRecord> observe(CompanionEntity companion) {
        if (companion == null || companion.level().isClientSide() || !(companion.level() instanceof ServerLevel level)) {
            return List.of();
        }
        UUID owner = companion.ownerUuid();
        if (owner == null) return List.of();
        TradeBook book = store.book(owner);
        book.activatePricingActor(companion.hands().player().getUUID());
        AABB bounds = companion.getBoundingBox().inflate(OBSERVATION_RADIUS);
        List<Entity> local = level.getEntitiesOfClass(Entity.class, bounds, entity -> entity instanceof Merchant
                && entity.isAlive() && entity.level() == level);
        var result = new java.util.ArrayList<TraderRecord>(Math.min(local.size(), 32));
        for (Entity entity : local.stream().sorted(Comparator.comparing(Entity::getUUID))
                .limit(dev.friendai.trading.TradeFile.MAX_TRADERS).toList()) {
            TraderRecord fact = TraderAdapters.presence(entity, level.getServer().overworld().getGameTime());
            book.observe(fact);
            result.add(fact);
        }
        return List.copyOf(result);
    }
}
