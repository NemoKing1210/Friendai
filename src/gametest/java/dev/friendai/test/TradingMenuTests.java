package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.ai.gossip.GossipType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

/** Exercises merchant payments and rewards through vanilla's real result slot. */
public class TradingMenuTests {
    // One inert hook for this class; each test removes its UUID-scoped behavior in finally.
    private static final Map<UUID, Consumer<Player>> INTERACTIONS = new ConcurrentHashMap<>();

    static {
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            Consumer<Player> action = INTERACTIONS.get(entity.getUUID());
            if (action == null) return InteractionResult.PASS;
            action.accept(player);
            return InteractionResult.FAIL;
        });
    }

    private record Fixture(CompanionEntity companion, Villager villager) {}

    private static Fixture setup(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setNoAi(true);
        Villager villager = h.spawn(EntityTypes.VILLAGER, 3, 2, 2);
        villager.setNoAi(true);
        villager.setVillagerData(villager.getVillagerData()
                .withProfession(h.getLevel().registryAccess(), VillagerProfession.LIBRARIAN));
        villager.setOffers(new MerchantOffers());
        return new Fixture(companion, villager);
    }

    private static MerchantMenu open(CompanionEntity companion, Entity target) {
        return companion.hands().openMerchant(target);
    }

    private static MerchantOffer bread(int maxUses) {
        return new MerchantOffer(new ItemCost(Items.EMERALD, 2), new ItemStack(Items.BREAD, 3), maxUses, 2, 0.05F);
    }

    private static int count(CompanionEntity companion, Item item) {
        int count = 0;
        for (int i = 0; i < companion.inventory().getContainerSize(); i++) {
            ItemStack stack = companion.inventory().getItem(i);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static int orbPoints(GameTestHelper h, Entity merchant) {
        return h.getLevel().getEntitiesOfClass(ExperienceOrb.class, merchant.getBoundingBox().inflate(2))
                .stream().mapToInt(ExperienceOrb::getValue).sum();
    }

    private static void payOnce(GameTestHelper h, CompanionEntity companion, MerchantMenu menu, int index) {
        MerchantOffer offer = menu.getOffers().get(index);
        menu.setSelectionHint(index);
        ItemStack costA = offer.getCostA();
        ItemStack costB = offer.getCostB();
        companion.inventory().setItem(25, costA.copyWithCount(costA.getCount() * 3));
        companion.inventory().setItem(26, costB.isEmpty() ? ItemStack.EMPTY : costB.copyWithCount(costB.getCount() * 3));
        // Only one transaction enters escrow, even when the companion can afford three.
        menu.getSlot(0).set(companion.inventory().removeItem(25, costA.getCount()));
        menu.getSlot(1).set(companion.inventory().removeItem(26, costB.getCount()));
        h.assertTrue(menu.stillValid(companion.hands().player()), "merchant session is invalid");
        h.assertTrue(menu.getSlot(2).mayPickup(companion.hands().player()), "result cannot be picked up");
        h.assertTrue(ItemStack.matches(menu.getSlot(2).getItem(), offer.getResult()), "result components changed");
        int uses = offer.getUses();
        ItemStack taken = menu.quickMoveStack(companion.hands().player(), 2);
        h.assertTrue(ItemStack.matches(taken, offer.getResult()), "vanilla take returned another result");
        h.assertTrue(offer.getUses() == uses + 1, "one take must consume exactly one use");
        h.assertTrue(menu.getSlot(0).getItem().isEmpty() && menu.getSlot(1).getItem().isEmpty(), "both costs must be consumed");
        h.assertTrue(menu.quickMoveStack(companion.hands().player(), 2).isEmpty(), "a second take must fail without payment");
        h.assertTrue(count(companion, Items.EMERALD) == costA.getCount() * 2, "first cost was overcharged");
        h.assertTrue(costB.isEmpty() || count(companion, Items.BOOK) == costB.getCount() * 2, "second cost was overcharged");
    }

    @GameTest
    public void villager_trade_uses_real_result_slot(GameTestHelper h) {
        Fixture f = setup(h);
        MerchantOffer bread = bread(8);
        ItemStack enchanted = new ItemStack(Items.ENCHANTED_BOOK);
        ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        enchantments.set(h.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.MENDING), 1);
        enchanted.set(DataComponents.STORED_ENCHANTMENTS, enchantments.toImmutable());
        MerchantOffer book = new MerchantOffer(new ItemCost(Items.EMERALD, 2),
                Optional.of(new ItemCost(Items.BOOK)), enchanted, 8, 3, 0.05F);
        f.villager().getOffers().add(bread);
        f.villager().getOffers().add(book);
        for (int index = 0; index < 2; index++) {
            int oldXp = f.villager().getVillagerXp();
            int oldOrbs = orbPoints(h, f.villager());
            MerchantMenu menu = open(f.companion(), f.villager());
            try {
                h.assertTrue(menu != null, "merchant did not open");
                payOnce(h, f.companion(), menu, index);
            } finally {
                f.companion().hands().closeMenu();
            }
            MerchantOffer offer = f.villager().getOffers().get(index);
            h.assertTrue(f.villager().getVillagerXp() == oldXp + offer.getXp(), "merchant XP was not awarded");
            int awarded = orbPoints(h, f.villager()) - oldOrbs;
            h.assertTrue(awarded >= 3 && awarded <= 6, "vanilla XP orb reward was " + awarded);
            h.assertTrue(f.companion().experience().points() == 0, "orb XP must not be directly awarded on close");
        }
        h.assertTrue(count(f.companion(), Items.BREAD) == 3, "bread output was not returned");
        h.assertTrue(count(f.companion(), Items.ENCHANTED_BOOK) == 1, "book output was not returned");
        boolean exactBook = false;
        for (int i = 0; i < f.companion().inventory().getContainerSize(); i++) {
            exactBook |= ItemStack.matches(f.companion().inventory().getItem(i), enchanted);
        }
        h.assertTrue(exactBook, "the purchased enchantment component was lost");
        h.assertTrue(f.villager().getTradingPlayer() == null, "merchant was not released");
        var orbs = h.getLevel().getEntitiesOfClass(ExperienceOrb.class, f.villager().getBoundingBox().inflate(2));
        int reward = orbs.stream().mapToInt(ExperienceOrb::getValue).sum();
        orbs.forEach(orb -> orb.snapTo(f.companion().getX(), f.companion().getY() + 0.5, f.companion().getZ()));
        h.runAfterDelay(2, () -> {
            h.assertTrue(f.companion().experience().points() == reward, "vanilla reward orbs were not picked up exactly once");
            h.runAfterDelay(5, () -> {
                h.assertTrue(f.companion().experience().points() == reward, "reward XP was counted twice");
                h.succeed();
            });
        });
    }

    @GameTest
    public void merchant_close_returns_unpaid_inputs(GameTestHelper h) {
        Fixture f = setup(h);
        MerchantOffer offer = new MerchantOffer(new ItemCost(Items.EMERALD, 2),
                Optional.of(new ItemCost(Items.BOOK)), new ItemStack(Items.ENCHANTED_BOOK), 8, 3, 0.05F);
        f.villager().getOffers().add(offer);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 4, 2, 2);
        f.companion().setOwner(owner.getUUID());
        f.villager().getGossips().add(owner.getUUID(), GossipType.MAJOR_POSITIVE, 100);
        f.villager().getGossips().add(f.companion().hands().player().getUUID(), GossipType.MINOR_NEGATIVE, 20);
        MerchantMenu menu = open(f.companion(), f.villager());
        try {
            h.assertTrue(menu != null, "merchant did not open");
            h.assertTrue(offer.getCostA().getCount() == 3, "prices did not use the fake player's negative reputation");
            h.assertTrue(f.villager().getPlayerReputation(owner) > 0, "owner reputation fixture is not distinct");
            f.companion().inventory().setItem(0, new ItemStack(Items.EMERALD, 3));
            f.companion().inventory().setItem(1, new ItemStack(Items.BOOK));
            menu.setSelectionHint(0);
            menu.getSlot(0).set(f.companion().inventory().removeItem(0, 3));
            menu.getSlot(1).set(f.companion().inventory().removeItem(1, 1));
            h.assertTrue(!menu.getSlot(2).getItem().isEmpty(), "unpaid preview is missing");
        } finally {
            f.companion().hands().closeMenu();
        }
        f.companion().hands().closeMenu();
        h.assertTrue(count(f.companion(), Items.EMERALD) == 3 && count(f.companion(), Items.BOOK) == 1,
                "unpaid inputs were lost or duplicated");
        h.assertTrue(count(f.companion(), Items.ENCHANTED_BOOK) == 0 && offer.getUses() == 0, "closing bought the preview");
        h.assertTrue(offer.getSpecialPriceDiff() == 0, "special price survived cleanup");
        CompanionEntity second = h.spawn(FriendaiEntities.COMPANION, 2, 2, 3);
        second.setNoAi(true);
        f.villager().getGossips().add(second.hands().player().getUUID(), GossipType.MAJOR_POSITIVE, 20);
        try {
            h.assertTrue(open(second, f.villager()) != null, "second actor did not open merchant");
            h.assertTrue(offer.getCostA().getCount() == 1, "second actor did not get its own positive-reputation price");
        } finally {
            second.hands().closeMenu();
        }
        h.assertTrue(offer.getSpecialPriceDiff() == 0, "second actor left stale prices");
        h.succeed();
    }

    @GameTest
    public void merchant_open_preserves_companion_xp(GameTestHelper h) {
        Fixture f = setup(h);
        f.villager().getOffers().add(bread(8));
        f.companion().experience().load(127);
        f.companion().syncExperience();
        try {
            h.assertTrue(open(f.companion(), f.villager()) != null, "merchant did not open");
            h.assertTrue(f.companion().hands().player().experienceLevel == f.companion().experience().level(), "XP was not mirrored");
        } finally {
            f.companion().hands().closeMenu();
        }
        h.assertTrue(f.companion().experience().points() == 127, "opening/closing overwrote companion XP");
        h.assertTrue(f.companion().syncedXpLevel() == f.companion().experience().level(), "synced XP changed");
        h.succeed();
    }

    @GameTest
    public void busy_merchant_is_not_stolen(GameTestHelper h) {
        Fixture f = setup(h);
        MerchantOffer offer = bread(8);
        f.villager().getOffers().add(offer);
        ServerPlayer human = GameTestSupport.mockPlayerAt(h, 4, 2, 2);
        f.villager().setTradingPlayer(human);
        offer.setSpecialPriceDiff(-1);
        h.assertTrue(open(f.companion(), f.villager()) == null, "busy human's merchant was stolen");
        f.companion().hands().closeMenu();
        h.assertTrue(f.villager().getTradingPlayer() == human && offer.getSpecialPriceDiff() == -1, "another actor's session was cleared");
        f.villager().setTradingPlayer(null);
        MerchantMenu first = open(f.companion(), f.villager());
        try {
            h.assertTrue(first != null, "first companion did not open merchant");
            CompanionEntity other = h.spawn(FriendaiEntities.COMPANION, 2, 2, 3);
            other.setNoAi(true);
            h.assertTrue(open(other, f.villager()) == null, "another companion stole the merchant");
            other.hands().closeMenu();
            h.assertTrue(f.villager().getTradingPlayer() == f.companion().hands().player(), "failed open released the first companion");
            f.companion().inventory().setItem(0, new ItemStack(Items.EMERALD, 2));
            first.getSlot(0).set(f.companion().inventory().removeItem(0, 2));
            f.companion().inventory().setItem(1, new ItemStack(Items.DIAMOND));
            first.setCarried(f.companion().inventory().removeItem(1, 1));
            f.villager().setTradingPlayer(human);
            offer.setSpecialPriceDiff(-1);
        } finally {
            f.companion().hands().closeMenu();
        }
        h.assertTrue(f.villager().getTradingPlayer() == human && offer.getSpecialPriceDiff() == -1, "stale cleanup erased the new customer's session");
        h.assertTrue(count(f.companion(), Items.EMERALD) == 2 && count(f.companion(), Items.DIAMOND) == 1
                && offer.getUses() == 0, "lost-session escrow or carried items were not returned");
        f.villager().setTradingPlayer(null);
        f.companion().hands().overrideProtection(pos -> true);
        h.assertTrue(open(f.companion(), f.villager()) == null, "protected merchant opened");
        f.companion().hands().overrideProtection(null);
        f.villager().setAge(-24000);
        h.assertTrue(open(f.companion(), f.villager()) == null, "baby merchant opened");
        f.villager().setAge(0);
        f.villager().setVillagerData(f.villager().getVillagerData().withProfession(h.getLevel().registryAccess(), VillagerProfession.NONE));
        h.assertTrue(open(f.companion(), f.villager()) == null, "unemployed merchant opened");
        f.villager().setVillagerData(f.villager().getVillagerData().withProfession(h.getLevel().registryAccess(), VillagerProfession.NITWIT));
        h.assertTrue(open(f.companion(), f.villager()) == null, "nitwit merchant opened");
        h.assertTrue(f.villager().getTradingPlayer() == null, "failed interaction retained merchant ownership");
        f.villager().setVillagerData(f.villager().getVillagerData()
                .withProfession(h.getLevel().registryAccess(), VillagerProfession.LIBRARIAN));
        f.villager().getOffers().clear();
        f.villager().getOffers().add(offer);
        f.villager().snapTo(f.companion().getX() + 8, f.companion().getY(), f.companion().getZ());
        h.assertTrue(open(f.companion(), f.villager()) == null, "distant merchant opened");
        f.villager().snapTo(f.companion().getX() + 1, f.companion().getY(), f.companion().getZ());
        try {
            INTERACTIONS.put(f.villager().getUUID(), player -> {});
            h.assertTrue(open(f.companion(), f.villager()) == null, "cancelled entity-use event was ignored");
            IllegalStateException failure = new IllegalStateException("test interaction failure");
            INTERACTIONS.put(f.villager().getUUID(), player -> {
                f.villager().setTradingPlayer(player);
                offer.setSpecialPriceDiff(-1);
                throw failure;
            });
            try {
                open(f.companion(), f.villager());
                h.assertTrue(false, "interaction failure was swallowed");
            } catch (IllegalStateException actual) {
                h.assertTrue(actual == failure, "unexpected interaction error");
            }
            h.assertTrue(f.villager().getTradingPlayer() == null && offer.getSpecialPriceDiff() == 0,
                    "failed open left ownership or special prices behind");
            h.assertTrue(f.companion().hands().player().containerMenu == f.companion().hands().player().inventoryMenu,
                    "failed open left a menu behind");
        } finally {
            INTERACTIONS.remove(f.villager().getUUID());
            f.companion().hands().closeMenu();
        }
        h.succeed();
    }

    @GameTest
    public void wandering_trader_does_not_restock(GameTestHelper h) {
        Fixture f = setup(h);
        WanderingTrader trader = h.spawn(EntityTypes.WANDERING_TRADER, 3, 2, 3);
        trader.setNoAi(true);
        trader.getOffers().clear();
        MerchantOffer offer = bread(1);
        trader.getOffers().add(offer);
        MerchantMenu menu = open(f.companion(), trader);
        try {
            h.assertTrue(menu != null, "wandering trader did not open");
            h.assertTrue(!menu.canRestock() && !trader.canRestock(), "wandering trader claims to restock");
            payOnce(h, f.companion(), menu, 0);
        } finally {
            f.companion().hands().closeMenu();
        }
        try {
            menu = open(f.companion(), trader);
            h.assertTrue(menu != null && offer.isOutOfStock() && offer.getUses() == 1, "reopening restocked trader");
            menu.setSelectionHint(0);
            menu.getSlot(0).set(f.companion().inventory().removeItem(0, 2));
            h.assertTrue(menu.getSlot(2).getItem().isEmpty(), "out-of-stock offer produced a result");
        } finally {
            f.companion().hands().closeMenu();
        }
        h.assertTrue(offer.getUses() == 1 && trader.getTradingPlayer() == null, "trader stock/session changed on close");
        h.succeed();
    }
}
