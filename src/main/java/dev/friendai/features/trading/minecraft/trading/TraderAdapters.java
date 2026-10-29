package dev.friendai.features.trading.minecraft.trading;

import dev.friendai.features.trading.TradeBook;
import dev.friendai.features.trading.TradeCost;
import dev.friendai.features.trading.TradeItem;
import dev.friendai.features.trading.TradeOffer;
import dev.friendai.features.trading.TraderRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

/** Narrow conversion from loaded merchant entities and vanilla offers to registry-independent observations. */
public final class TraderAdapters {
    private TraderAdapters() {}

    /** Captures only loaded entity/location facts. It intentionally does not inspect or open the merchant menu. */
    public static TraderRecord presence(Entity entity, long nowTick) {
        if (entity == null || entity.level().isClientSide() || nowTick < 0) {
            throw new IllegalArgumentException("loaded server entity and nonnegative tick are required");
        }
        TraderRecord.Status status = entity instanceof Merchant merchant && merchant.getTradingPlayer() != null
                ? TraderRecord.Status.BUSY
                : TraderRecord.Status.SEEN;
        return record(entity, nowTick, -1, status, List.of());
    }

    /** Converts every current vanilla offer after an interaction-based inspection. */
    public static TraderRecord verified(
            Entity entity, MerchantOffers offers, UUID pricingActor, long nowTick, HolderLookup.Provider registries) {
        if (entity == null
                || entity.level().isClientSide()
                || offers == null
                || pricingActor == null
                || registries == null
                || nowTick < 0) {
            throw new IllegalArgumentException("server merchant, offers, actor, registries and tick are required");
        }
        return record(
                entity,
                nowTick,
                nowTick,
                TraderRecord.Status.VERIFIED,
                offers(offers, pricingActor, nowTick, registries));
    }

    public static List<TradeOffer> offers(
            MerchantOffers offers, UUID pricingActor, long nowTick, HolderLookup.Provider registries) {
        if (offers == null || pricingActor == null || registries == null || nowTick < 0) {
            throw new IllegalArgumentException("offers, pricing actor, registries and tick are required");
        }
        Map<String, Integer> duplicateCounts = new HashMap<>();
        var result = new ArrayList<TradeOffer>(offers.size());
        for (MerchantOffer offer : offers) {
            List<TradeCost> base = baseInputs(offer, registries);
            TradeItem output = TradeItemCodec.encode(offer.getResult(), registries);
            String identity = TradeOffer.fingerprint(base, output, 0);
            int discriminator = duplicateCounts.merge(identity, 1, Integer::sum) - 1;
            result.add(offer(offer, pricingActor, nowTick, registries, discriminator, base, output));
        }
        return List.copyOf(result);
    }

    public static TradeOffer offer(
            MerchantOffer offer,
            UUID pricingActor,
            long nowTick,
            HolderLookup.Provider registries,
            int duplicateDiscriminator) {
        if (offer == null || pricingActor == null || registries == null || nowTick < 0 || duplicateDiscriminator < 0) {
            throw new IllegalArgumentException("offer, pricing actor, registries and nonnegative tick are required");
        }
        List<TradeCost> base = baseInputs(offer, registries);
        TradeItem output = TradeItemCodec.encode(offer.getResult(), registries);
        return offer(offer, pricingActor, nowTick, registries, duplicateDiscriminator, base, output);
    }

    /** Resolves a cached quote only when it is unambiguous and was priced by the current companion actor. */
    public static Optional<TradeOffer> quoteForActor(TradeBook book, UUID trader, String offerRef, UUID actor) {
        if (book == null || trader == null || offerRef == null || actor == null) return Optional.empty();
        return book.resolveOffer(trader, offerRef).filter(offer -> actor.equals(offer.pricingActor()));
    }

    /** Extracts the semantic identity of the offer, excluding its changing effective price and list index. */
    public static String semanticRef(MerchantOffer offer, HolderLookup.Provider registries) {
        List<TradeCost> base = baseInputs(offer, registries);
        TradeItem output = TradeItemCodec.encode(offer.getResult(), registries);
        TradeOffer identity = offer(offer, new UUID(0, 1), 0, registries, 0, base, output);
        return identity.ref();
    }

    private static TradeOffer offer(
            MerchantOffer offer,
            UUID actor,
            long tick,
            HolderLookup.Provider registries,
            int discriminator,
            List<TradeCost> base,
            TradeItem output) {
        List<TradeCost> effective = new ArrayList<>(2);
        ItemStack first = offer.getCostA();
        effective.add(new TradeCost(base.getFirst().itemId(), base.getFirst().predicateJson(), first.getCount()));
        if (base.size() == 2) {
            ItemStack second = offer.getCostB();
            effective.add(new TradeCost(base.get(1).itemId(), base.get(1).predicateJson(), second.getCount()));
        }
        String fingerprint = TradeOffer.fingerprint(base, output, discriminator);
        var temporary = new TradeOffer(
                "pending",
                fingerprint,
                base,
                effective,
                output,
                offer.getUses(),
                offer.getMaxUses(),
                offer.getXp(),
                actor,
                tick);
        return new TradeOffer(
                temporary.stableRef(),
                fingerprint,
                base,
                effective,
                output,
                offer.getUses(),
                offer.getMaxUses(),
                offer.getXp(),
                actor,
                tick);
    }

    private static List<TradeCost> baseInputs(MerchantOffer offer, HolderLookup.Provider registries) {
        List<TradeCost> result = new ArrayList<>(2);
        ItemCost first = offer.getItemCostA();
        result.add(TradeItemCodec.encode(first, registries));
        offer.getItemCostB().ifPresent(cost -> result.add(TradeItemCodec.encode(cost, registries)));
        return List.copyOf(result);
    }

    private static TraderRecord record(
            Entity entity, long tick, long offersSeen, TraderRecord.Status status, List<TradeOffer> offers) {
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        String profession = profession(entity);
        int level =
                entity instanceof Villager villager ? villager.getVillagerData().level() : 0;
        return new TraderRecord(
                entity.getUUID(),
                type,
                entity.level().dimension().identifier().toString(),
                entity.getBlockX(),
                entity.getBlockY(),
                entity.getBlockZ(),
                label(entity.getName().getString(), type),
                profession,
                level,
                tick,
                tick,
                offersSeen,
                0,
                status,
                offers);
    }

    private static String label(String name, String fallback) {
        StringBuilder safe = new StringBuilder(Math.min(name.length(), 80));
        name.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint))
                .limit(80)
                .forEach(safe::appendCodePoint);
        return safe.isEmpty() ? fallback : safe.toString();
    }

    private static String profession(Entity entity) {
        if (entity instanceof Villager villager) {
            return BuiltInRegistries.VILLAGER_PROFESSION
                    .getKey(villager.getVillagerData().profession().value())
                    .toString();
        }
        return entity instanceof WanderingTrader ? "minecraft:wandering_trader" : "";
    }
}
