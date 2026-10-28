package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.features.trading.TradeAuthority;
import dev.friendai.features.trading.TradeBudget;
import dev.friendai.features.trading.TradeOrder;
import dev.friendai.features.trading.TradeProgress;
import dev.friendai.features.trading.TraderRecord;
import dev.friendai.infrastructure.persistence.trading.TradeStore;
import dev.friendai.task.trading.MerchantAdapter;
import dev.friendai.task.trading.MerchantLeases;
import dev.friendai.task.trading.MerchantSession;
import dev.friendai.task.trading.TradeSessionGuard;
import dev.friendai.task.trading.TraderObserver;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponentExactPredicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

/** Exercises order validation, escrow and one vanilla result-slot transaction. */
public final class TradingTransactionTests {
    private record Fixture(
            CompanionEntity companion,
            Villager villager,
            ServerPlayer owner,
            TradeStore store,
            MerchantLeases leases,
            TradeSessionGuard guard,
            MerchantAdapter adapter,
            TraderObserver observer)
            implements AutoCloseable {
        @Override
        public void close() {
            companion.hands().closeMenu();
            guard.closeAll("GameTest cleanup");
            store.close();
        }
    }

    private record Authorized(TradeOrder order, TradeAuthority authority) {}

    private static Fixture setup(GameTestHelper helper, String label) {
        return setup(helper, label, false);
    }

    private static Fixture setup(GameTestHelper helper, String label, boolean throwingMerchant) {
        GameTestSupport.platform(helper, 14);
        CompanionEntity companion = helper.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setNoAi(true);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(helper, 4, 2, 2);
        companion.setOwner(owner.getUUID());
        Villager villager;
        if (throwingMerchant) {
            Villager original = helper.spawn(EntityTypes.VILLAGER, 3, 2, 2);
            ThrowOnceVillager replacement = new ThrowOnceVillager(EntityTypes.VILLAGER, helper.getLevel());
            replacement.setPos(original.position());
            original.discard();
            helper.getLevel().addFreshEntity(replacement);
            villager = replacement;
        } else {
            villager = helper.spawn(EntityTypes.VILLAGER, 3, 2, 2);
        }
        villager.setNoAi(true);
        villager.setVillagerData(villager.getVillagerData()
                .withProfession(helper.getLevel().registryAccess(), VillagerProfession.LIBRARIAN));
        villager.setOffers(new MerchantOffers());
        var store = new TradeStore(helper.getLevel()
                .getServer()
                .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("friendai-test-trading-" + label));
        var leases = new MerchantLeases();
        var guard = new TradeSessionGuard(leases);
        return new Fixture(
                companion,
                villager,
                owner,
                store,
                leases,
                guard,
                new MerchantAdapter(store, leases, guard),
                new TraderObserver(store));
    }

    /** Throws once at an armed getTradingPlayer read, allowing the session failure boundary to be exercised. */
    private static final class ThrowOnceVillager extends Villager {
        private int readsUntilThrow;

        private ThrowOnceVillager(
                net.minecraft.world.entity.EntityType<? extends Villager> type, net.minecraft.world.level.Level level) {
            super(type, level);
        }

        private void throwOnRead(int read) {
            readsUntilThrow = read;
        }

        @Override
        public Player getTradingPlayer() {
            if (readsUntilThrow > 0 && --readsUntilThrow == 0) {
                readsUntilThrow = -1;
                throw new IllegalStateException("injected merchant interruption");
            }
            return super.getTradingPlayer();
        }
    }

    private static Authorized authorize(
            Fixture fixture, TraderRecord trader, int desired, Map<String, Integer> caps, boolean allowPartial) {
        UUID owner = fixture.owner().getUUID();
        var authority = new TradeAuthority(owner);
        Caller caller = authority.begin(new Caller(owner, "owner", AccessLevel.OWNER), true);
        var requested = new TradeOrder(
                UUID.randomUUID(),
                owner,
                caller.tradingScope(),
                fixture.villager().getUUID(),
                trader.offers().getFirst().ref(),
                trader.revision(),
                desired,
                new TradeBudget(caps, Map.of(), Map.of()),
                Set.of(),
                false,
                allowPartial,
                // An absolute deadline, as production builds it: the order is evaluated against the world clock,
                // which the shared GameTest world has already advanced past any small literal.
                fixture.companion().level().getGameTime() + 200);
        return new Authorized(authority.authorize(caller, requested), authority);
    }

    private static int count(CompanionEntity companion, net.minecraft.world.item.Item item) {
        int total = 0;
        for (int i = 0; i < companion.inventory().getContainerSize(); i++) {
            ItemStack stack = companion.inventory().getItem(i);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    @GameTest
    public void two_input_trade_uses_vanilla_result_and_preserves_components(GameTestHelper helper) {
        Fixture f = setup(helper, "success");
        try (f) {
            ItemStack enchanted = new ItemStack(Items.ENCHANTED_BOOK);
            ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
            enchantments.set(
                    helper.getLevel()
                            .registryAccess()
                            .lookupOrThrow(Registries.ENCHANTMENT)
                            .getOrThrow(Enchantments.MENDING),
                    1);
            enchanted.set(DataComponents.STORED_ENCHANTMENTS, enchantments.toImmutable());
            MerchantOffer offer = new MerchantOffer(
                    new ItemCost(Items.EMERALD, 2), Optional.of(new ItemCost(Items.BOOK)), enchanted, 8, 3, 0.05F);
            f.villager().getOffers().add(offer);

            TraderRecord quote = f.adapter()
                    .inspect(f.companion(), f.villager(), helper.getLevel().getGameTime())
                    .orElseThrow();
            Authorized authorized = authorize(f, quote, 1, Map.of("minecraft:emerald", 4, "minecraft:book", 2), true);
            TradeOrder order = authorized.order();
            TradeAuthority authority = authorized.authority();
            f.companion().inventory().setItem(0, new ItemStack(Items.EMERALD, 4));
            f.companion().inventory().setItem(1, new ItemStack(Items.BOOK, 2));

            MerchantSession.Outcome outcome;
            try (var session = f.adapter()
                    .openSession(
                            f.companion(),
                            f.villager(),
                            order,
                            authority,
                            helper.getLevel().getGameTime())) {
                outcome = session.tradeOnce(order);
            }

            helper.assertTrue(outcome.traded(), "authorized two-input trade did not run: " + outcome.reason());
            helper.assertTrue(offer.getUses() == 1, "vanilla use count did not increment exactly once");
            helper.assertTrue(
                    count(f.companion(), Items.EMERALD) == 2 && count(f.companion(), Items.BOOK) == 1,
                    "inputs were not charged exactly once");
            helper.assertTrue(
                    count(f.companion(), Items.ENCHANTED_BOOK) == 1,
                    "the vanilla result was not returned to the companion");
            boolean exact = false;
            for (int i = 0; i < f.companion().inventory().getContainerSize(); i++) {
                exact |= ItemStack.matches(f.companion().inventory().getItem(i), enchanted);
            }
            helper.assertTrue(exact, "enchanted output components changed");
            helper.assertTrue(
                    outcome.progress().spent().equals(Map.of("minecraft:emerald", 2, "minecraft:book", 1)),
                    "ledger did not reconcile both payments");
            helper.assertTrue(
                    outcome.progress().received().size() == 1
                            && outcome.progress()
                                    .received()
                                    .getFirst()
                                    .componentsJson()
                                    .contains("minecraft:mending"),
                    "ledger did not preserve the exact output component");
            helper.assertTrue(f.villager().getTradingPlayer() == null, "merchant customer was not released");
            helper.succeed();
        }
    }

    @GameTest
    public void quote_for_another_pricing_actor_is_denied_before_payment(GameTestHelper helper) {
        Fixture f = setup(helper, "actor-change");
        try (f) {
            f.villager()
                    .getOffers()
                    .add(new MerchantOffer(new ItemCost(Items.EMERALD, 2), new ItemStack(Items.BREAD), 8, 1, 0.05F));
            TraderRecord quote = f.adapter()
                    .inspect(f.companion(), f.villager(), helper.getLevel().getGameTime())
                    .orElseThrow();
            Authorized authorized = authorize(f, quote, 1, Map.of("minecraft:emerald", 4), true);
            TradeOrder order = authorized.order();
            TradeAuthority authority = authorized.authority();
            f.companion().inventory().setItem(0, new ItemStack(Items.EMERALD, 4));
            CompanionEntity replacement = helper.spawn(FriendaiEntities.COMPANION, 2, 2, 3);
            replacement.setNoAi(true);
            replacement.setOwner(f.owner().getUUID());

            boolean denied = false;
            try {
                f.adapter()
                        .openSession(
                                replacement,
                                f.villager(),
                                order,
                                authority,
                                helper.getLevel().getGameTime());
            } catch (IllegalStateException expected) {
                denied = true;
            }
            helper.assertTrue(denied, "stale quote actor was allowed to open a trade session");
            helper.assertTrue(
                    count(f.companion(), Items.EMERALD) == 4
                            && f.villager().getOffers().getFirst().getUses() == 0,
                    "actor mismatch changed inventory or merchant stock");
            helper.assertTrue(f.villager().getTradingPlayer() == null, "actor mismatch left a merchant menu open");
            helper.succeed();
        }
    }

    @GameTest
    public void semantic_duplicate_after_reordering_is_rejected(GameTestHelper helper) {
        Fixture f = setup(helper, "reordered-duplicate");
        try (f) {
            MerchantOffer selected =
                    new MerchantOffer(new ItemCost(Items.EMERALD, 2), new ItemStack(Items.BREAD), 8, 1, 0.05F);
            f.villager().getOffers().add(selected);
            TraderRecord quote = f.adapter()
                    .inspect(f.companion(), f.villager(), helper.getLevel().getGameTime())
                    .orElseThrow();
            Authorized authorized = authorize(f, quote, 1, Map.of("minecraft:emerald", 4), true);
            MerchantOffer duplicate =
                    new MerchantOffer(new ItemCost(Items.EMERALD, 2), new ItemStack(Items.BREAD), 12, 1, 0.05F);
            duplicate.increaseUses();
            f.villager().getOffers().clear();
            f.villager().getOffers().add(duplicate);
            f.villager().getOffers().add(selected);
            f.companion().inventory().setItem(0, new ItemStack(Items.EMERALD, 4));

            boolean denied = false;
            try {
                f.adapter()
                        .openSession(
                                f.companion(),
                                f.villager(),
                                authorized.order(),
                                authorized.authority(),
                                helper.getLevel().getGameTime());
            } catch (IllegalStateException expected) {
                denied = true;
            }
            helper.assertTrue(denied, "a duplicate semantic offer could be retargeted by reordering");
            helper.assertTrue(
                    count(f.companion(), Items.EMERALD) == 4 && selected.getUses() == 0 && duplicate.getUses() == 1,
                    "ambiguous offer changed stock or inventory");
            helper.assertTrue(f.villager().getTradingPlayer() == null, "ambiguous quote left merchant open");
            helper.succeed();
        }
    }

    @GameTest
    public void authorized_order_can_continue_one_fill_per_session(GameTestHelper helper) {
        Fixture f = setup(helper, "two-fills");
        try (f) {
            MerchantOffer offer =
                    new MerchantOffer(new ItemCost(Items.EMERALD, 2), new ItemStack(Items.BREAD), 8, 1, 0.05F);
            f.villager().getOffers().add(offer);
            TraderRecord quote = f.adapter()
                    .inspect(f.companion(), f.villager(), helper.getLevel().getGameTime())
                    .orElseThrow();
            Authorized authorized = authorize(f, quote, 2, Map.of("minecraft:emerald", 4), false);
            f.companion().inventory().setItem(0, new ItemStack(Items.EMERALD, 4));

            MerchantSession.Outcome first;
            try (var session = f.adapter()
                    .openSession(
                            f.companion(),
                            f.villager(),
                            authorized.order(),
                            authorized.authority(),
                            helper.getLevel().getGameTime())) {
                first = session.tradeOnce(authorized.order());
            }
            helper.assertTrue(
                    first.traded() && first.progress().state() == TradeProgress.State.RUNNING,
                    "first fill did not leave a resumable order");
            MerchantSession.Outcome second;
            try (var session = f.adapter()
                    .openSession(
                            f.companion(),
                            f.villager(),
                            authorized.order(),
                            authorized.authority(),
                            helper.getLevel().getGameTime())) {
                second = session.tradeOnce(authorized.order());
            }
            helper.assertTrue(
                    second.traded() && second.progress().state() == TradeProgress.State.COMPLETED,
                    "second fill did not complete the same authorized order: " + second.reason());
            helper.assertTrue(
                    offer.getUses() == 2
                            && count(f.companion(), Items.EMERALD) == 0
                            && count(f.companion(), Items.BREAD) == 2,
                    "two fills did not conserve inputs and outputs");
            helper.succeed();
        }
    }

    @GameTest
    public void current_price_increase_within_cap_is_charged(GameTestHelper helper) {
        Fixture f = setup(helper, "price-within-cap");
        try (f) {
            MerchantOffer offer =
                    new MerchantOffer(new ItemCost(Items.EMERALD, 2), new ItemStack(Items.BREAD), 8, 2, 0.05F);
            f.villager().getOffers().add(offer);
            TraderRecord quote = f.adapter()
                    .inspect(f.companion(), f.villager(), helper.getLevel().getGameTime())
                    .orElseThrow();
            Authorized authorized = authorize(f, quote, 1, Map.of("minecraft:emerald", 3), true);
            f.companion().inventory().setItem(0, new ItemStack(Items.EMERALD, 3));
            MerchantSession.Outcome outcome;
            try (var session = f.adapter()
                    .openSession(
                            f.companion(),
                            f.villager(),
                            authorized.order(),
                            authorized.authority(),
                            helper.getLevel().getGameTime())) {
                offer.setSpecialPriceDiff(1);
                outcome = session.tradeOnce(authorized.order());
            }
            helper.assertTrue(
                    outcome.traded(), "a live price within the authorized cap was denied: " + outcome.reason());
            helper.assertTrue(
                    count(f.companion(), Items.EMERALD) == 0
                            && outcome.progress().spent().getOrDefault("minecraft:emerald", 0) == 3,
                    "the transaction did not charge the current price of three emeralds");
            helper.assertTrue(
                    offer.getUses() == 1 && count(f.companion(), Items.BREAD) == 1,
                    "the within-cap transaction did not complete exactly once");
            helper.succeed();
        }
    }

    @GameTest
    public void current_price_increase_over_cap_is_denied_without_payment(GameTestHelper helper) {
        Fixture f = setup(helper, "price-over-cap");
        try (f) {
            MerchantOffer offer =
                    new MerchantOffer(new ItemCost(Items.EMERALD, 2), new ItemStack(Items.BREAD), 8, 2, 0.05F);
            f.villager().getOffers().add(offer);
            TraderRecord quote = f.adapter()
                    .inspect(f.companion(), f.villager(), helper.getLevel().getGameTime())
                    .orElseThrow();
            Authorized authorized = authorize(f, quote, 1, Map.of("minecraft:emerald", 2), true);
            f.companion().inventory().setItem(0, new ItemStack(Items.EMERALD, 3));
            MerchantSession.Outcome outcome;
            try (var session = f.adapter()
                    .openSession(
                            f.companion(),
                            f.villager(),
                            authorized.order(),
                            authorized.authority(),
                            helper.getLevel().getGameTime())) {
                offer.setSpecialPriceDiff(1);
                outcome = session.tradeOnce(authorized.order());
            }
            helper.assertTrue(!outcome.traded(), "a current price above the authorized cap was accepted");
            helper.assertTrue(
                    count(f.companion(), Items.EMERALD) == 3
                            && offer.getUses() == 0
                            && count(f.companion(), Items.BREAD) == 0,
                    "the denied price increase changed payment, stock, or output");
            helper.succeed();
        }
    }

    @GameTest
    public void custom_named_payment_is_protected_without_explicit_allowance(GameTestHelper helper) {
        Fixture f = setup(helper, "protected-payment");
        try (f) {
            MerchantOffer offer =
                    new MerchantOffer(new ItemCost(Items.EMERALD, 2), new ItemStack(Items.BREAD), 8, 2, 0.05F);
            f.villager().getOffers().add(offer);
            TraderRecord quote = f.adapter()
                    .inspect(f.companion(), f.villager(), helper.getLevel().getGameTime())
                    .orElseThrow();
            Authorized authorized = authorize(f, quote, 1, Map.of("minecraft:emerald", 4), true);
            ItemStack named = new ItemStack(Items.EMERALD, 4);
            named.set(DataComponents.CUSTOM_NAME, Component.literal("keepsake"));
            f.companion().inventory().setItem(0, named.copy());
            MerchantSession.Outcome outcome;
            try (var session = f.adapter()
                    .openSession(
                            f.companion(),
                            f.villager(),
                            authorized.order(),
                            authorized.authority(),
                            helper.getLevel().getGameTime())) {
                outcome = session.tradeOnce(authorized.order());
            }
            helper.assertTrue(!outcome.traded(), "a named payment stack was spent without permission");
            helper.assertTrue(
                    ItemStack.matches(f.companion().inventory().getItem(0), named)
                            && offer.getUses() == 0
                            && count(f.companion(), Items.BREAD) == 0,
                    "protected custom payment changed during the denied transaction");
            helper.succeed();
        }
    }

    @GameTest
    public void wrong_exact_enchantment_cannot_pay_offer(GameTestHelper helper) {
        Fixture f = setup(helper, "wrong-enchantment");
        try (f) {
            var enchantments = helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
            ItemEnchantments.Mutable requiredEnchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
            requiredEnchantments.set(enchantments.getOrThrow(Enchantments.SHARPNESS), 1);
            ItemEnchantments.Mutable actualEnchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
            actualEnchantments.set(enchantments.getOrThrow(Enchantments.MENDING), 1);
            MerchantOffer offer = new MerchantOffer(
                    new ItemCost(
                            Items.ENCHANTED_BOOK.builtInRegistryHolder(),
                            1,
                            DataComponentExactPredicate.expect(
                                    DataComponents.STORED_ENCHANTMENTS, requiredEnchantments.toImmutable())),
                    new ItemStack(Items.BREAD),
                    8,
                    1,
                    0.05F);
            f.villager().getOffers().add(offer);
            TraderRecord quote = f.adapter()
                    .inspect(f.companion(), f.villager(), helper.getLevel().getGameTime())
                    .orElseThrow();
            Authorized authorized = authorize(f, quote, 1, Map.of("minecraft:enchanted_book", 1), true);
            ItemStack wrongBook = new ItemStack(Items.ENCHANTED_BOOK);
            wrongBook.set(DataComponents.STORED_ENCHANTMENTS, actualEnchantments.toImmutable());
            f.companion().inventory().setItem(0, wrongBook.copy());
            MerchantSession.Outcome outcome;
            try (var session = f.adapter()
                    .openSession(
                            f.companion(),
                            f.villager(),
                            authorized.order(),
                            authorized.authority(),
                            helper.getLevel().getGameTime())) {
                outcome = session.tradeOnce(authorized.order());
            }
            helper.assertTrue(!outcome.traded(), "book with a different exact enchantment paid the offer");
            helper.assertTrue(
                    ItemStack.matches(f.companion().inventory().getItem(0), wrongBook)
                            && offer.getUses() == 0
                            && count(f.companion(), Items.BREAD) == 0,
                    "wrong-enchantment denial changed the payment, stock, or output");
            helper.succeed();
        }
    }

    @GameTest
    public void full_inventory_denies_output_before_payment(GameTestHelper helper) {
        Fixture f = setup(helper, "full-inventory");
        try (f) {
            MerchantOffer offer =
                    new MerchantOffer(new ItemCost(Items.EMERALD, 2), new ItemStack(Items.BREAD), 8, 2, 0.05F);
            f.villager().getOffers().add(offer);
            TraderRecord quote = f.adapter()
                    .inspect(f.companion(), f.villager(), helper.getLevel().getGameTime())
                    .orElseThrow();
            Authorized authorized = authorize(f, quote, 1, Map.of("minecraft:emerald", 2), true);
            f.companion().inventory().setItem(0, new ItemStack(Items.EMERALD, 64));
            for (int slot = 1; slot < f.companion().inventory().getContainerSize(); slot++) {
                f.companion().inventory().setItem(slot, new ItemStack(Items.STONE, 64));
            }
            MerchantSession.Outcome outcome;
            try (var session = f.adapter()
                    .openSession(
                            f.companion(),
                            f.villager(),
                            authorized.order(),
                            authorized.authority(),
                            helper.getLevel().getGameTime())) {
                outcome = session.tradeOnce(authorized.order());
            }
            helper.assertTrue(!outcome.traded(), "transaction ran although its output would not fit");
            helper.assertTrue(
                    count(f.companion(), Items.EMERALD) == 64 && offer.getUses() == 0,
                    "output-capacity denial charged emeralds or advanced stock");
            helper.succeed();
        }
    }

    @GameTest
    public void exhausted_offer_does_not_charge_a_second_fill(GameTestHelper helper) {
        Fixture f = setup(helper, "exhausted-stock");
        try (f) {
            MerchantOffer offer =
                    new MerchantOffer(new ItemCost(Items.EMERALD, 2), new ItemStack(Items.BREAD), 1, 1, 0.05F);
            f.villager().getOffers().add(offer);
            TraderRecord quote = f.adapter()
                    .inspect(f.companion(), f.villager(), helper.getLevel().getGameTime())
                    .orElseThrow();
            Authorized authorized = authorize(f, quote, 2, Map.of("minecraft:emerald", 4), true);
            f.companion().inventory().setItem(0, new ItemStack(Items.EMERALD, 4));
            MerchantSession.Outcome first;
            try (var session = f.adapter()
                    .openSession(
                            f.companion(),
                            f.villager(),
                            authorized.order(),
                            authorized.authority(),
                            helper.getLevel().getGameTime())) {
                first = session.tradeOnce(authorized.order());
            }
            MerchantSession.Outcome second;
            try (var session = f.adapter()
                    .openSession(
                            f.companion(),
                            f.villager(),
                            authorized.order(),
                            authorized.authority(),
                            helper.getLevel().getGameTime())) {
                second = session.tradeOnce(authorized.order());
            }
            helper.assertTrue(first.traded() && !second.traded(), "exhausted offer did not stop the next fill");
            helper.assertTrue(
                    offer.getUses() == 1
                            && count(f.companion(), Items.EMERALD) == 2
                            && count(f.companion(), Items.BREAD) == 1,
                    "second attempt spent payment or changed the exhausted offer");
            helper.succeed();
        }
    }

    @GameTest
    public void busy_human_merchant_is_not_taken_over(GameTestHelper helper) {
        Fixture f = setup(helper, "busy-human");
        try (f) {
            MerchantOffer offer =
                    new MerchantOffer(new ItemCost(Items.EMERALD, 2), new ItemStack(Items.BREAD), 8, 1, 0.05F);
            f.villager().getOffers().add(offer);
            TraderRecord quote = f.adapter()
                    .inspect(f.companion(), f.villager(), helper.getLevel().getGameTime())
                    .orElseThrow();
            Authorized authorized = authorize(f, quote, 1, Map.of("minecraft:emerald", 2), true);
            f.villager().setTradingPlayer(f.owner());
            boolean denied = false;
            try {
                f.adapter()
                        .openSession(
                                f.companion(),
                                f.villager(),
                                authorized.order(),
                                authorized.authority(),
                                helper.getLevel().getGameTime());
            } catch (IllegalStateException expected) {
                denied = true;
            }
            helper.assertTrue(
                    denied && f.villager().getTradingPlayer() == f.owner(),
                    "the active human merchant session was replaced");
            helper.assertTrue(
                    offer.getUses() == 0
                            && !f.leases()
                                    .heldBy(
                                            f.villager().getUUID(),
                                            authorized.order().orderId()),
                    "denial modified stock or kept a merchant lease");
            f.villager().setTradingPlayer(null);
            helper.succeed();
        }
    }

    @GameTest
    public void protection_denial_does_not_open_or_spend(GameTestHelper helper) {
        Fixture f = setup(helper, "protection-denial");
        try (f) {
            MerchantOffer offer =
                    new MerchantOffer(new ItemCost(Items.EMERALD, 2), new ItemStack(Items.BREAD), 8, 1, 0.05F);
            f.villager().getOffers().add(offer);
            TraderRecord quote = f.adapter()
                    .inspect(f.companion(), f.villager(), helper.getLevel().getGameTime())
                    .orElseThrow();
            Authorized authorized = authorize(f, quote, 1, Map.of("minecraft:emerald", 2), true);
            f.companion().inventory().setItem(0, new ItemStack(Items.EMERALD, 2));
            f.companion().hands().overrideProtection(pos -> true);
            boolean denied = false;
            try {
                f.adapter()
                        .openSession(
                                f.companion(),
                                f.villager(),
                                authorized.order(),
                                authorized.authority(),
                                helper.getLevel().getGameTime());
            } catch (IllegalStateException expected) {
                denied = true;
            } finally {
                f.companion().hands().overrideProtection(null);
            }
            helper.assertTrue(
                    denied && f.villager().getTradingPlayer() == null,
                    "protection denial opened or retained a merchant menu");
            helper.assertTrue(
                    count(f.companion(), Items.EMERALD) == 2
                            && offer.getUses() == 0
                            && count(f.companion(), Items.BREAD) == 0
                            && !f.leases()
                                    .heldBy(
                                            f.villager().getUUID(),
                                            authorized.order().orderId()),
                    "protection denial changed inventory/stock or kept a lease");
            helper.succeed();
        }
    }

    @GameTest
    public void paused_task_guard_closes_session_and_releases_lease(GameTestHelper helper) {
        Fixture f = setup(helper, "paused-guard");
        try (f) {
            MerchantOffer offer =
                    new MerchantOffer(new ItemCost(Items.EMERALD, 2), new ItemStack(Items.BREAD), 8, 1, 0.05F);
            f.villager().getOffers().add(offer);
            TraderRecord quote = f.adapter()
                    .inspect(f.companion(), f.villager(), helper.getLevel().getGameTime())
                    .orElseThrow();
            Authorized authorized = authorize(f, quote, 1, Map.of("minecraft:emerald", 2), true);
            f.companion().inventory().setItem(0, new ItemStack(Items.EMERALD, 2));
            MerchantSession session = f.adapter()
                    .openSession(
                            f.companion(),
                            f.villager(),
                            authorized.order(),
                            authorized.authority(),
                            helper.getLevel().getGameTime());
            f.companion().executionCoordinator().setPaused(true);
            f.guard().tick(helper.getLevel().getGameTime());
            helper.assertTrue(
                    f.villager().getTradingPlayer() == null
                            && !f.leases()
                                    .heldBy(
                                            f.villager().getUUID(),
                                            authorized.order().orderId()),
                    "guard did not release the merchant after the companion was paused");
            helper.assertTrue(
                    count(f.companion(), Items.EMERALD) == 2 && offer.getUses() == 0,
                    "guard cleanup changed an unattempted transaction");
            session.close();
            f.companion().executionCoordinator().setPaused(false);
            helper.succeed();
        }
    }

    @GameTest
    public void exception_after_escrow_returns_payment_and_releases_reservation(GameTestHelper helper) {
        Fixture f = setup(helper, "escrow-exception", true);
        try (f) {
            MerchantOffer offer =
                    new MerchantOffer(new ItemCost(Items.EMERALD, 2), new ItemStack(Items.BREAD), 8, 1, 0.05F);
            f.villager().getOffers().add(offer);
            TraderRecord quote = f.adapter()
                    .inspect(f.companion(), f.villager(), helper.getLevel().getGameTime())
                    .orElseThrow();
            Authorized authorized = authorize(f, quote, 1, Map.of("minecraft:emerald", 2), true);
            f.companion().inventory().setItem(0, new ItemStack(Items.EMERALD, 2));
            MerchantSession session = f.adapter()
                    .openSession(
                            f.companion(),
                            f.villager(),
                            authorized.order(),
                            authorized.authority(),
                            helper.getLevel().getGameTime());
            ((ThrowOnceVillager) f.villager()).throwOnRead(2);
            MerchantSession.Outcome outcome = session.tradeOnce(authorized.order());
            helper.assertTrue(
                    !outcome.traded() && outcome.progress().state() == TradeProgress.State.AUTHORIZED,
                    "pre-use exception was not treated as safely retryable");
            helper.assertTrue(
                    count(f.companion(), Items.EMERALD) == 2
                            && offer.getUses() == 0
                            && count(f.companion(), Items.BREAD) == 0,
                    "escrow was not returned after pre-use failure");
            helper.assertTrue(
                    authorized.authority().reserve(authorized.order(), Map.of("minecraft:emerald", 2)),
                    "pre-use failure kept the payment reservation");
            authorized.authority().releaseReservation(authorized.order(), Map.of("minecraft:emerald", 2));
            helper.succeed();
        }
    }
}
