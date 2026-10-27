package dev.friendai.agent.autonomy;

import dev.friendai.crafting.obtain.Tier;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What free time knows about the companion and its base at one moment, read on the server thread.
 *
 * @param foodPoints the hunger points the food in the bag would restore
 * @param armor the weakest tier among the worn armor pieces, {@link Tier#NONE} without armor
 * @param craftingTable a crafting table in the bag or at home
 * @param furnace a furnace in the bag or at home
 * @param home the nearest house place, in any dimension
 * @param storageFreeSlots free slots in the containers of the storage places (or of home without one)
 * @param furnacesReady furnaces at home with a finished result to collect
 * @param hasFuel the bag holds something that burns
 */
public record AutonomyFacts(
        int freeSlots,
        int foodPoints,
        int health,
        int maxHealth,
        Map<StockKind, Integer> stock,
        Tier pickaxe,
        Tier axe,
        Tier sword,
        Tier armor,
        int armorPieces,
        boolean shield,
        boolean bucket,
        boolean craftingTable,
        boolean furnace,
        boolean dark,
        boolean inCombat,
        boolean ownerOnline,
        double ownerDistance,
        Optional<PlaceRef> home,
        List<PlaceRef> storages,
        List<FarmRef> farms,
        int storageFreeSlots,
        int furnacesReady,
        boolean hasFuel,
        long nowMillis) {

    public AutonomyFacts {
        stock = stock == null || stock.isEmpty() ? Map.of() : Map.copyOf(new EnumMap<>(stock));
        pickaxe = pickaxe == null ? Tier.NONE : pickaxe;
        axe = axe == null ? Tier.NONE : axe;
        sword = sword == null ? Tier.NONE : sword;
        armor = armor == null ? Tier.NONE : armor;
        home = home == null ? Optional.empty() : home;
        storages = storages == null ? List.of() : List.copyOf(storages);
        farms = farms == null ? List.of() : List.copyOf(farms);
    }

    public int count(StockKind kind) {
        return stock.getOrDefault(kind, 0);
    }

    /** Home, when it is in the companion's dimension. */
    public Optional<PlaceRef> homeHere() {
        return home.filter(PlaceRef::sameDimension);
    }

    /** The nearest storage place in the companion's dimension. */
    public Optional<PlaceRef> storageHere() {
        return storages.stream().filter(PlaceRef::sameDimension)
                .min(java.util.Comparator.comparingDouble(PlaceRef::distance));
    }
}
