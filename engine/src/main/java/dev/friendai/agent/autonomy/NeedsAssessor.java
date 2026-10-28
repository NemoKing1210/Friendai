package dev.friendai.agent.autonomy;

import dev.friendai.foundation.item.Tier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Turns the facts into the agenda: what the companion needs most, the way a survival player would judge it. */
public final class NeedsAssessor {
    public static final int MAX_NEEDS = 6;

    static final int SHELTER_DISTANCE = 24;
    static final int FULL_BAG_SLOTS = 3;
    static final int HUNGRY_POINTS = 20;
    static final int RAW_ORE_BATCH = 8;
    static final long STALE_FARM_MILLIS = 15 * 60_000L;
    static final long STALE_TRADE_MILLIS = 30 * 60_000L;
    static final int TORCHES_LOW = 16;
    static final int COAL_FOR_TORCHES = 2;
    static final int XP_FOR_ENCHANT = 5;
    static final int OBSIDIAN_FOR_PORTAL = 10;

    private NeedsAssessor() {}

    public static List<Need> assess(AutonomyFacts f, FreeTime state, AutonomySettings s) {
        List<Need> needs = new ArrayList<>();
        if (f.health() * 10 < f.maxHealth() * 4) {
            needs.add(new Need(
                    NeedKind.SURVIVE,
                    100,
                    Executor.REFLEX,
                    "health is low (" + f.health() + "/" + f.maxHealth() + "); get to safety and recover"));
        }
        if (f.self().hungry(f.foodPoints())) {
            needs.add(new Need(
                    NeedKind.HUNGER,
                    95,
                    Executor.REFLEX,
                    f.foodPoints() > 0
                            ? "hungry (" + f.self().hunger() + "/20); eat the food in the bag"
                            : "hungry (" + f.self().hunger() + "/20) and no food in the bag; get food"));
        }
        Optional<PlaceRef> home = f.homeHere();
        if (f.dark()
                && home.isPresent()
                && !f.self().bedAtHome()
                && (home.get().distance() > SHELTER_DISTANCE || f.armor().ordinal() < Tier.IRON.ordinal())) {
            needs.add(new Need(
                    NeedKind.SHELTER,
                    90,
                    Executor.REFLEX,
                    "it is dark; go home to " + home.get().name() + " until morning"));
        }
        if (f.dark() && home.isPresent() && f.self().bedAtHome()) {
            needs.add(new Need(
                    NeedKind.SLEEP,
                    88,
                    Executor.REFLEX,
                    "it is night and there is a bed at " + home.get().name() + "; sleep until morning"));
        }
        Optional<PlaceRef> unloadAt = f.storageHere().or(() -> home);
        if (f.freeSlots() <= FULL_BAG_SLOTS && unloadAt.isPresent() && f.storageFreeSlots() > 0) {
            needs.add(new Need(
                    NeedKind.UNLOAD,
                    80,
                    Executor.REFLEX,
                    "the bag is almost full (" + f.freeSlots() + " free slots); deposit loot at "
                            + unloadAt.get().name()));
        }
        if (f.foodPoints() < HUNGRY_POINTS) {
            needs.add(new Need(
                    NeedKind.FOOD,
                    75,
                    Executor.DIRECTOR,
                    "food is low (" + f.foodPoints()
                            + " hunger points in the bag); cook, harvest, hunt or breed food"));
        }
        if (f.furnacesReady() > 0 && home.isPresent()) {
            needs.add(new Need(
                    NeedKind.COLLECT,
                    60,
                    Executor.REFLEX,
                    f.furnacesReady() + " furnace(s) at home have finished; collect the results"));
        }
        if (f.self().gearWorn()) {
            needs.add(new Need(
                    NeedKind.REPAIR,
                    58,
                    Executor.DIRECTOR,
                    "gear is nearly broken (" + Math.round(f.self().gearWear() * 100)
                            + "% left); repair it at an anvil or replace it with obtain_item"));
        }
        LadderView ladder = ProgressionLadder.evaluate(f);
        if (!ladder.missingGear().isEmpty()) {
            needs.add(new Need(
                    NeedKind.GEAR,
                    55,
                    Executor.DIRECTOR,
                    "gear for stage " + ladder.stage() + ": missing " + String.join(", ", ladder.missingGear())));
        }
        if (f.count(StockKind.RAW_ORE) >= RAW_ORE_BATCH && f.hasFuel()) {
            needs.add(new Need(
                    NeedKind.PROCESS,
                    50,
                    Executor.DIRECTOR,
                    f.count(StockKind.RAW_ORE) + " raw ore in the bag; smelt it"));
        }
        f.farms().stream()
                .filter(FarmRef::sameDimension)
                .filter(farm -> farm.millisSinceVisit() >= STALE_FARM_MILLIS)
                .max(Comparator.comparingLong(FarmRef::millisSinceVisit))
                .ifPresent(farm -> needs.add(new Need(
                        NeedKind.FARM,
                        45,
                        Executor.DIRECTOR,
                        farm.name() + " was not tended for " + farm.millisSinceVisit() / 60_000 + " min")));
        boolean farmHere = f.farms().stream().anyMatch(FarmRef::sameDimension);
        if (s.allowBuilding() && home.isPresent() && !farmHere && f.count(StockKind.SEEDS) > 0) {
            needs.add(new Need(
                    NeedKind.FARM_CREATE,
                    44,
                    Executor.REFLEX,
                    "no farm here; till and sow a plot near " + home.get().name()));
        } else if (home.isPresent() && !farmHere && f.foodPoints() < HUNGRY_POINTS) {
            needs.add(new Need(
                    NeedKind.ANIMALS,
                    43,
                    Executor.DIRECTOR,
                    "food is low and there is no farm; breed or hunt animals near home"));
        }
        if (f.count(StockKind.TORCHES) < TORCHES_LOW && f.count(StockKind.COAL) >= COAL_FOR_TORCHES) {
            needs.add(new Need(
                    NeedKind.LIGHT,
                    40,
                    Executor.DIRECTOR,
                    "only " + f.count(StockKind.TORCHES) + " torches; craft more from coal"));
        }
        boolean storageLacking = f.storageHere().isEmpty() || f.storageFreeSlots() == 0;
        if (s.allowBuilding() && home.isPresent() && storageLacking) {
            needs.add(new Need(
                    NeedKind.STORAGE,
                    37,
                    Executor.DIRECTOR,
                    "storage is missing or full; craft a chest, place it at "
                            + home.get().name() + " and remember it as storage"));
        }
        if (s.allowBuilding() && home.isEmpty()) {
            needs.add(new Need(
                    NeedKind.BUILD,
                    35,
                    Executor.DIRECTOR,
                    "there is no home here; build a small house with build_structure (generator house); "
                            + "the finished building is remembered as home"));
        }
        f.trades().stream()
                .filter(TradeRef::sameDimension)
                .filter(trade -> trade.millisSinceVisit() >= STALE_TRADE_MILLIS)
                .max(Comparator.comparingLong(TradeRef::millisSinceVisit))
                .ifPresent(trade -> needs.add(new Need(
                        NeedKind.TRADE,
                        34,
                        Executor.DIRECTOR,
                        trade.name() + " was not visited for " + trade.millisSinceVisit() / 60_000
                                + " min; trade there")));
        if (f.self().xpLevel() >= XP_FOR_ENCHANT && f.self().enchantingTableAtHome() && gearAtLeastIron(f)) {
            needs.add(new Need(
                    NeedKind.ENCHANT,
                    33,
                    Executor.DIRECTOR,
                    f.self().xpLevel() + " levels and an enchanting table at home; enchant the best gear"));
        }
        if (s.allowBuilding()
                && !f.portalKnown()
                && f.self().flintAndSteel()
                && f.self().obsidian() >= OBSIDIAN_FOR_PORTAL
                && ladder.complete()) {
            needs.add(new Need(
                    NeedKind.NETHER,
                    32,
                    Executor.DIRECTOR,
                    "the ladder is complete and the bag has flint and steel and obsidian; build a nether portal "
                            + "with build_special and gather ancient debris"));
        }
        if (!ladder.missingStock().isEmpty()) {
            needs.add(new Need(
                    NeedKind.STOCK,
                    30,
                    Executor.DIRECTOR,
                    "stock for stage " + ladder.stage() + ": " + String.join(", ", ladder.missingStock())));
        }
        needs.add(new Need(
                NeedKind.EXPLORE, 10, Executor.DIRECTOR, "nothing urgent; explore, gather or improve the base"));
        return needs.stream()
                .filter(need -> !coolingDown(state, need.kind(), f.nowMillis()))
                .sorted(Comparator.comparingInt(Need::urgency)
                        .reversed()
                        .thenComparingInt(need -> need.kind().ordinal()))
                .limit(MAX_NEEDS)
                .toList();
    }

    private static boolean gearAtLeastIron(AutonomyFacts f) {
        return f.pickaxe().ordinal() >= Tier.IRON.ordinal()
                || f.sword().ordinal() >= Tier.IRON.ordinal()
                || f.armor().ordinal() >= Tier.IRON.ordinal();
    }

    /**
     * The need the director named, or the most urgent director need when the name is missing or unknown. A known
     * name wins even when its need is cooling down: the director may choose work the filtered agenda no longer shows.
     */
    public static NeedKind kindNamed(String name, List<Need> needs) {
        if (name != null && !name.isBlank()) {
            String wanted = name.strip();
            for (NeedKind kind : NeedKind.values()) {
                if (kind.name().equalsIgnoreCase(wanted)) {
                    return kind;
                }
            }
        }
        return needs.stream()
                .filter(need -> need.executor() == Executor.DIRECTOR)
                .map(Need::kind)
                .findFirst()
                .orElse(NeedKind.EXPLORE);
    }

    private static boolean coolingDown(FreeTime state, NeedKind kind, long now) {
        Long until = state.cooldownUntil().get(kind);
        return until != null && until > now;
    }
}
