package dev.friendai.agent.autonomy;

import dev.friendai.foundation.item.Tier;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Builds {@link AutonomyFacts} for tests: a healthy, fed companion at day with an empty bag and no places. */
final class Facts {
    static final long NOW = 1_000_000;

    private int freeSlots = 20;
    private int foodPoints = 20;
    private int health = 20;
    private int maxHealth = 20;
    private final Map<StockKind, Integer> stock = new EnumMap<>(StockKind.class);
    private Tier pickaxe = Tier.NONE;
    private Tier axe = Tier.NONE;
    private Tier sword = Tier.NONE;
    private Tier armor = Tier.NONE;
    private int armorPieces;
    private boolean shield;
    private boolean bucket;
    private boolean craftingTable;
    private boolean furnace;
    private boolean dark;
    private boolean inCombat;
    private boolean ownerOnline = true;
    private double ownerDistance = 5;
    private PlaceRef home;
    private final List<PlaceRef> storages = new ArrayList<>();
    private final List<FarmRef> farms = new ArrayList<>();
    private int storageFreeSlots;
    private int furnacesReady;
    private boolean hasFuel;
    private int hunger = 20;
    private int xpLevel;
    private double gearWear = 1.0;
    private boolean bedAtHome;
    private boolean enchantingTableAtHome;
    private boolean anvilAtHome;
    private boolean flintAndSteel;
    private int obsidian;
    private final List<TradeRef> trades = new ArrayList<>();
    private boolean portalKnown;
    private long now = NOW;
    private long nextId = 1;
    private Runnable lastPlace = () -> {};

    static Facts base() {
        return new Facts();
    }

    Facts freeSlots(int v) {
        freeSlots = v;
        return this;
    }

    Facts foodPoints(int v) {
        foodPoints = v;
        return this;
    }

    Facts health(int v) {
        health = v;
        return this;
    }

    Facts stock(StockKind kind, int v) {
        stock.put(kind, v);
        return this;
    }

    Facts pickaxe(Tier v) {
        pickaxe = v;
        return this;
    }

    Facts axe(Tier v) {
        axe = v;
        return this;
    }

    Facts sword(Tier v) {
        sword = v;
        return this;
    }

    Facts armor(Tier v, int pieces) {
        armor = v;
        armorPieces = pieces;
        return this;
    }

    Facts shield() {
        shield = true;
        return this;
    }

    Facts bucket() {
        bucket = true;
        return this;
    }

    Facts craftingTable() {
        craftingTable = true;
        return this;
    }

    Facts craftingTable(boolean value) {
        craftingTable = value;
        return this;
    }

    Facts furnace() {
        furnace = true;
        return this;
    }

    Facts furnace(boolean value) {
        furnace = value;
        return this;
    }

    Facts dark() {
        dark = true;
        return this;
    }

    Facts dark(boolean value) {
        dark = value;
        return this;
    }

    Facts inCombat() {
        inCombat = true;
        return this;
    }

    Facts ownerOffline() {
        ownerOnline = false;
        return this;
    }

    Facts storageFreeSlots(int v) {
        storageFreeSlots = v;
        return this;
    }

    Facts furnacesReady(int v) {
        furnacesReady = v;
        return this;
    }

    Facts hasFuel() {
        hasFuel = true;
        return this;
    }

    Facts hunger(int v) {
        hunger = v;
        return this;
    }

    Facts xpLevel(int v) {
        xpLevel = v;
        return this;
    }

    Facts gearWear(double v) {
        gearWear = v;
        return this;
    }

    Facts bedAtHome() {
        bedAtHome = true;
        return this;
    }

    Facts enchantingTableAtHome() {
        enchantingTableAtHome = true;
        return this;
    }

    Facts anvilAtHome() {
        anvilAtHome = true;
        return this;
    }

    Facts flintAndSteel() {
        flintAndSteel = true;
        return this;
    }

    Facts obsidian(int v) {
        obsidian = v;
        return this;
    }

    Facts portalKnown() {
        portalKnown = true;
        return this;
    }

    Facts trade(String name, double distance, long millisSinceVisit) {
        trades.add(new TradeRef(nextId++, name, distance, true, millisSinceVisit));
        return this;
    }

    Facts now(long v) {
        now = v;
        return this;
    }

    Facts home(String name, double distance) {
        long id = nextId++;
        home = new PlaceRef(id, name, distance, true);
        lastPlace = () -> home = new PlaceRef(id, name, distance, false);
        return this;
    }

    Facts storage(String name, double distance) {
        long id = nextId++;
        storages.add(new PlaceRef(id, name, distance, true));
        int index = storages.size() - 1;
        lastPlace = () -> storages.set(index, new PlaceRef(id, name, distance, false));
        return this;
    }

    Facts farm(String name, String label, double distance, long millisSinceVisit) {
        long id = nextId++;
        farms.add(new FarmRef(id, name, label, distance, true, millisSinceVisit));
        int index = farms.size() - 1;
        lastPlace = () -> farms.set(index, new FarmRef(id, name, label, distance, false, millisSinceVisit));
        return this;
    }

    /** Moves the place added last into another dimension. */
    Facts otherDimension() {
        lastPlace.run();
        return this;
    }

    /** A companion that has everything the ladder asks for. */
    Facts everything() {
        pickaxe = Tier.DIAMOND;
        axe = Tier.DIAMOND;
        sword = Tier.DIAMOND;
        armor = Tier.IRON;
        armorPieces = 4;
        shield = true;
        bucket = true;
        craftingTable = true;
        furnace = true;
        for (StockKind kind : StockKind.values()) {
            stock.put(kind, 64);
        }
        return this;
    }

    AutonomyFacts build() {
        return new AutonomyFacts(
                freeSlots,
                foodPoints,
                health,
                maxHealth,
                stock,
                pickaxe,
                axe,
                sword,
                armor,
                armorPieces,
                shield,
                bucket,
                craftingTable,
                furnace,
                dark,
                inCombat,
                ownerOnline,
                ownerDistance,
                Optional.ofNullable(home),
                storages,
                farms,
                storageFreeSlots,
                furnacesReady,
                hasFuel,
                new SelfFacts(
                        hunger,
                        xpLevel,
                        gearWear,
                        bedAtHome,
                        enchantingTableAtHome,
                        anvilAtHome,
                        flintAndSteel,
                        obsidian),
                trades,
                portalKnown,
                now);
    }
}
