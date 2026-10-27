package dev.friendai.agent.autonomy;

import dev.friendai.crafting.obtain.Tier;
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
    static final int TORCHES_LOW = 16;
    static final int COAL_FOR_TORCHES = 2;
    static final int PLANKS_FOR_STORAGE = 16;

    private NeedsAssessor() {}

    public static List<Need> assess(AutonomyFacts f, FreeTime state, AutonomySettings s) {
        List<Need> needs = new ArrayList<>();
        if (f.health() * 10 < f.maxHealth() * 4) {
            needs.add(new Need(NeedKind.SURVIVE, 100, Executor.REFLEX,
                    "health is low (" + f.health() + "/" + f.maxHealth() + "); get to safety and recover"));
        }
        Optional<PlaceRef> home = f.homeHere();
        if (f.dark() && home.isPresent()
                && (home.get().distance() > SHELTER_DISTANCE || f.armor().ordinal() < Tier.IRON.ordinal())) {
            needs.add(new Need(NeedKind.SHELTER, 90, Executor.REFLEX,
                    "it is dark; go home to " + home.get().name() + " until morning"));
        }
        Optional<PlaceRef> unloadAt = f.storageHere().or(() -> home);
        if (f.freeSlots() <= FULL_BAG_SLOTS && unloadAt.isPresent() && f.storageFreeSlots() > 0) {
            needs.add(new Need(NeedKind.UNLOAD, 80, Executor.REFLEX,
                    "the bag is almost full (" + f.freeSlots() + " free slots); deposit loot at "
                            + unloadAt.get().name()));
        }
        if (f.foodPoints() < HUNGRY_POINTS) {
            needs.add(new Need(NeedKind.FOOD, 75, Executor.DIRECTOR,
                    "food is low (" + f.foodPoints() + " hunger points in the bag); get or cook food"));
        }
        if (f.furnacesReady() > 0 && home.isPresent()) {
            needs.add(new Need(NeedKind.COLLECT, 60, Executor.REFLEX,
                    f.furnacesReady() + " furnace(s) at home have finished; collect the results"));
        }
        LadderView ladder = ProgressionLadder.evaluate(f);
        if (!ladder.missingGear().isEmpty()) {
            needs.add(new Need(NeedKind.GEAR, 55, Executor.DIRECTOR,
                    "gear for stage " + ladder.stage() + ": missing " + String.join(", ", ladder.missingGear())));
        }
        if (f.count(StockKind.RAW_ORE) >= RAW_ORE_BATCH && f.hasFuel()) {
            needs.add(new Need(NeedKind.PROCESS, 50, Executor.DIRECTOR,
                    f.count(StockKind.RAW_ORE) + " raw ore in the bag; smelt it"));
        }
        f.farms().stream()
                .filter(FarmRef::sameDimension)
                .filter(farm -> farm.millisSinceVisit() >= STALE_FARM_MILLIS)
                .max(Comparator.comparingLong(FarmRef::millisSinceVisit))
                .ifPresent(farm -> needs.add(new Need(NeedKind.FARM, 45, Executor.DIRECTOR,
                        farm.name() + " was not tended for " + farm.millisSinceVisit() / 60_000 + " min")));
        if (f.count(StockKind.TORCHES) < TORCHES_LOW && f.count(StockKind.COAL) >= COAL_FOR_TORCHES) {
            needs.add(new Need(NeedKind.LIGHT, 40, Executor.DIRECTOR,
                    "only " + f.count(StockKind.TORCHES) + " torches; craft more from coal"));
        }
        if (s.allowBuilding()) {
            boolean storageLacking = f.storageHere().isEmpty() || f.storageFreeSlots() == 0;
            if (home.isEmpty()) {
                needs.add(new Need(NeedKind.BUILD, 35, Executor.DIRECTOR,
                        "there is no home here; a small shelter would make a base"));
            } else if (storageLacking && f.count(StockKind.PLANKS) >= PLANKS_FOR_STORAGE) {
                needs.add(new Need(NeedKind.BUILD, 35, Executor.DIRECTOR,
                        "storage is missing or full; build chests at " + home.get().name()));
            }
        }
        if (!ladder.missingStock().isEmpty()) {
            needs.add(new Need(NeedKind.STOCK, 30, Executor.DIRECTOR,
                    "stock for stage " + ladder.stage() + ": " + String.join(", ", ladder.missingStock())));
        }
        needs.add(new Need(NeedKind.EXPLORE, 10, Executor.DIRECTOR,
                "nothing urgent; explore, gather or improve the base"));
        return needs.stream()
                .filter(need -> !coolingDown(state, need.kind(), f.nowMillis()))
                .sorted(Comparator.comparingInt(Need::urgency).reversed()
                        .thenComparingInt(need -> need.kind().ordinal()))
                .limit(MAX_NEEDS)
                .toList();
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
