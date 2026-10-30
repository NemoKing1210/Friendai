package dev.friendai.features.trading.minecraft.trading;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.trading.TradeBook;
import dev.friendai.features.trading.TradeBookRepository;
import dev.friendai.features.trading.TraderRecord;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.phys.AABB;

/** Read-only observations of loaded merchants and adult piglins; never opens a menu or loads a chunk. */
public final class TraderObserver {
    public static final int OBSERVATION_RADIUS = 64;

    private final TradeBookRepository store;
    private final IntSupplier radius;
    private final BooleanSupplier enabled;

    public TraderObserver(TradeBookRepository store) {
        this(store, () -> OBSERVATION_RADIUS, () -> true);
    }

    public TraderObserver(TradeBookRepository store, IntSupplier radius, BooleanSupplier enabled) {
        this.store = java.util.Objects.requireNonNull(store);
        this.radius = java.util.Objects.requireNonNull(radius);
        this.enabled = java.util.Objects.requireNonNull(enabled);
    }

    /** Refreshes locations for nearby loaded merchants while retaining previously inspected offer quotes. */
    public List<TraderRecord> observe(CompanionEntity companion) {
        if (!enabled.getAsBoolean()) return List.of();
        if (companion == null
                || companion.level().isClientSide()
                || !(companion.level() instanceof ServerLevel level)) {
            return List.of();
        }
        UUID owner = companion.ownerUuid();
        if (owner == null) return List.of();
        TradeBook book = store.book(owner);
        book.activatePricingActor(companion.hands().player().getUUID());
        AABB bounds = companion.getBoundingBox().inflate(Math.max(8, Math.min(64, radius.getAsInt())));
        List<Entity> local = level.getEntitiesOfClass(
                Entity.class,
                bounds,
                entity -> (entity instanceof Merchant || (entity instanceof Piglin piglin && !piglin.isBaby()))
                        && entity.isAlive()
                        && entity.level() == level);
        var result = new java.util.ArrayList<TraderRecord>(Math.min(local.size(), 32));
        for (Entity entity : local.stream()
                .sorted(Comparator.comparing(Entity::getUUID))
                .limit(dev.friendai.features.trading.TradeFile.MAX_TRADERS)
                .toList()) {
            TraderRecord fact = TraderAdapters.presence(
                    entity, level.getServer().overworld().getGameTime());
            book.observe(fact);
            result.add(fact);
        }
        return List.copyOf(result);
    }
}
