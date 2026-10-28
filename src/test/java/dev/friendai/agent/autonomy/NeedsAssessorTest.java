package dev.friendai.agent.autonomy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.crafting.obtain.Tier;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NeedsAssessorTest {
    private static final AutonomySettings S = AutonomySettings.DEFAULTS;

    private static List<Need> needs(Facts facts) {
        return NeedsAssessor.assess(facts.build(), FreeTime.OFF, S);
    }

    private static List<NeedKind> kinds(List<Need> needs) {
        return needs.stream().map(Need::kind).toList();
    }

    @Test
    void fullBagWithStorageIsUnloadReflex() {
        List<Need> needs = needs(Facts.base().freeSlots(2).storage("storage", 30).storageFreeSlots(27));
        assertEquals(NeedKind.UNLOAD, needs.get(0).kind());
        assertEquals(Executor.REFLEX, needs.get(0).executor());
    }

    @Test
    void fullBagUsesHomeWithoutStorage() {
        assertTrue(kinds(needs(Facts.base().freeSlots(2).home("home", 30).storageFreeSlots(5)))
                .contains(NeedKind.UNLOAD));
    }

    @Test
    void fullBagWithoutStorageIsNotUnload() {
        assertFalse(kinds(needs(Facts.base().freeSlots(2))).contains(NeedKind.UNLOAD));
        assertFalse(kinds(needs(Facts.base().freeSlots(2).storage("storage", 30).storageFreeSlots(0)))
                .contains(NeedKind.UNLOAD));
    }

    @Test
    void lowHealthIsSurviveFirst() {
        List<Need> needs = needs(Facts.base().health(7).freeSlots(2).storage("s", 5).storageFreeSlots(9));
        assertEquals(NeedKind.SURVIVE, needs.get(0).kind());
        assertEquals(100, needs.get(0).urgency());
        assertFalse(kinds(needs(Facts.base().health(8))).contains(NeedKind.SURVIVE));
    }

    @Test
    void nightFarFromHomeIsShelter() {
        assertTrue(kinds(needs(Facts.base().dark().home("home", 40).armor(Tier.IRON, 4)))
                .contains(NeedKind.SHELTER));
        assertTrue(kinds(needs(Facts.base().dark().home("home", 10))).contains(NeedKind.SHELTER));
        assertFalse(kinds(needs(Facts.base().dark().home("home", 10).armor(Tier.IRON, 4)))
                .contains(NeedKind.SHELTER));
        assertFalse(kinds(needs(Facts.base().home("home", 40))).contains(NeedKind.SHELTER));
    }

    @Test
    void readyFurnaceAtHomeIsCollect() {
        assertTrue(kinds(needs(Facts.base().home("home", 3).furnacesReady(1))).contains(NeedKind.COLLECT));
    }

    @Test
    void hungryIsFood() {
        assertTrue(kinds(needs(Facts.base().foodPoints(19))).contains(NeedKind.FOOD));
        assertFalse(kinds(needs(Facts.base().foodPoints(20))).contains(NeedKind.FOOD));
    }

    @Test
    void rawOreWithFuelIsProcess() {
        assertTrue(kinds(needs(Facts.base().stock(StockKind.RAW_ORE, 8).hasFuel())).contains(NeedKind.PROCESS));
        assertFalse(kinds(needs(Facts.base().stock(StockKind.RAW_ORE, 8))).contains(NeedKind.PROCESS));
    }

    @Test
    void staleFarmIsFarm() {
        assertTrue(kinds(needs(Facts.base().farm("wheat farm", "wheat", 20, 16 * 60_000L)))
                .contains(NeedKind.FARM));
        assertFalse(kinds(needs(Facts.base().farm("wheat farm", "wheat", 20, 10 * 60_000L)))
                .contains(NeedKind.FARM));
    }

    @Test
    void torchesWithCoalIsLight() {
        assertTrue(kinds(needs(Facts.base().stock(StockKind.COAL, 2))).contains(NeedKind.LIGHT));
        assertFalse(kinds(needs(Facts.base().stock(StockKind.COAL, 2).stock(StockKind.TORCHES, 16)))
                .contains(NeedKind.LIGHT));
    }

    @Test
    void noHomeIsBuildUnlessDisallowed() {
        assertTrue(kinds(NeedsAssessor.assess(Facts.base().everything().build(), FreeTime.OFF, S))
                .contains(NeedKind.BUILD));
        var noBuild = new AutonomySettings(true, true, 5, 60, true, 20, 6, 2, 10, true, false, List.of());
        assertFalse(kinds(NeedsAssessor.assess(Facts.base().everything().build(), FreeTime.OFF, noBuild))
                .contains(NeedKind.BUILD));
    }

    @Test
    void ladderGapsAreGearAndStock() {
        List<NeedKind> kinds = kinds(needs(Facts.base()));
        assertTrue(kinds.contains(NeedKind.GEAR));
        assertTrue(kinds.contains(NeedKind.STOCK));
        assertTrue(needs(Facts.base()).stream().filter(n -> n.kind() == NeedKind.GEAR)
                .allMatch(n -> n.text().contains("wooden pickaxe")));
    }

    @Test
    void hungerIsASelfCareReflex() {
        List<Need> starving = needs(Facts.base().hunger(6).foodPoints(0));
        assertEquals(NeedKind.HUNGER, starving.get(0).kind());
        assertEquals(Executor.REFLEX, starving.get(0).executor());
        assertFalse(kinds(needs(Facts.base().hunger(12).foodPoints(0))).contains(NeedKind.HUNGER));
        assertTrue(kinds(needs(Facts.base().hunger(10).foodPoints(0))).contains(NeedKind.HUNGER));
        assertFalse(kinds(needs(Facts.base().hunger(10).foodPoints(8))).contains(NeedKind.HUNGER));
    }

    @Test
    void nightWithABedIsSleepNotShelter() {
        List<NeedKind> bed = kinds(needs(Facts.base().dark().home("home", 40).bedAtHome()));
        assertTrue(bed.contains(NeedKind.SLEEP));
        assertFalse(bed.contains(NeedKind.SHELTER));
        List<NeedKind> noBed = kinds(needs(Facts.base().dark().home("home", 40)));
        assertTrue(noBed.contains(NeedKind.SHELTER));
        assertFalse(noBed.contains(NeedKind.SLEEP));
    }

    @Test
    void wornGearIsRepair() {
        assertTrue(kinds(needs(Facts.base().pickaxe(Tier.IRON).gearWear(0.1))).contains(NeedKind.REPAIR));
        assertFalse(kinds(needs(Facts.base().pickaxe(Tier.IRON).gearWear(0.5))).contains(NeedKind.REPAIR));
    }

    @Test
    void homeWithSeedsCreatesAFarm() {
        assertTrue(kinds(needs(Facts.base().home("home", 8).stock(StockKind.SEEDS, 3)))
                .contains(NeedKind.FARM_CREATE));
        assertFalse(kinds(needs(Facts.base().home("home", 8))).contains(NeedKind.FARM_CREATE));
        assertFalse(kinds(needs(Facts.base().stock(StockKind.SEEDS, 3))).contains(NeedKind.FARM_CREATE));
        var noBuild = new AutonomySettings(true, true, 5, 60, true, 20, 6, 2, 10, true, false, List.of());
        assertFalse(kinds(NeedsAssessor.assess(Facts.base().home("home", 8).stock(StockKind.SEEDS, 3).build(),
                FreeTime.OFF, noBuild)).contains(NeedKind.FARM_CREATE));
    }

    @Test
    void homeStorageAndProgress() {
        assertTrue(kinds(needs(Facts.base().home("home", 8))).contains(NeedKind.STORAGE));
        assertFalse(kinds(needs(Facts.base().home("home", 8).storage("s", 4).storageFreeSlots(9)))
                .contains(NeedKind.STORAGE));
        assertTrue(kinds(needs(Facts.base().trade("village", 20, 40 * 60_000L))).contains(NeedKind.TRADE));
        assertFalse(kinds(needs(Facts.base().trade("village", 20, 60_000L))).contains(NeedKind.TRADE));
        assertTrue(kinds(needs(Facts.base().pickaxe(Tier.IRON).xpLevel(9).home("home", 4)
                .enchantingTableAtHome())).contains(NeedKind.ENCHANT));
        assertTrue(kinds(needs(Facts.base().everything().flintAndSteel().obsidian(10)
                .home("home", 4))).contains(NeedKind.NETHER));
        assertFalse(kinds(needs(Facts.base().everything().portalKnown().flintAndSteel().obsidian(10)
                .home("home", 4))).contains(NeedKind.NETHER));
    }

    @Test
    void safetyNeedsSurviveTheSixItemCap() {
        List<NeedKind> kinds = kinds(needs(Facts.base().health(4).hunger(5).dark().home("home", 40)
                .freeSlots(1).storageFreeSlots(9).furnacesReady(2).foodPoints(0)
                .stock(StockKind.RAW_ORE, 9).hasFuel().gearWear(0.1)));
        assertTrue(kinds.contains(NeedKind.SURVIVE));
        assertTrue(kinds.contains(NeedKind.HUNGER));
        assertTrue(kinds.contains(NeedKind.SHELTER));
    }

    @Test
    void theDirectorNamePicksTheAgendaNeed() {
        List<Need> needs = needs(Facts.base());
        assertEquals(NeedKind.STOCK, NeedsAssessor.kindNamed("stock", needs));
        assertEquals(NeedKind.STOCK, NeedsAssessor.kindNamed(" STOCK ", needs));
        assertEquals(NeedKind.GEAR, NeedsAssessor.kindNamed("nonsense", needs));
        assertEquals(NeedKind.GEAR, NeedsAssessor.kindNamed("", needs));
    }

    @Test
    void atMostSixSorted() {
        List<Need> needs = needs(Facts.base().health(5).dark().home("home", 40).freeSlots(1).storageFreeSlots(9)
                .furnacesReady(2).foodPoints(0).stock(StockKind.RAW_ORE, 9).hasFuel().stock(StockKind.COAL, 3)
                .farm("f", "wheat", 5, 3_600_000));
        assertEquals(NeedsAssessor.MAX_NEEDS, needs.size());
        for (int i = 1; i < needs.size(); i++) {
            assertTrue(needs.get(i - 1).urgency() >= needs.get(i).urgency());
        }
        assertEquals(List.of(NeedKind.SURVIVE, NeedKind.SHELTER, NeedKind.UNLOAD, NeedKind.FOOD,
                NeedKind.COLLECT, NeedKind.GEAR), kinds(needs));
    }

    @Test
    void exploreAlwaysLast() {
        List<Need> needs = needs(Facts.base().everything().home("home", 2));
        assertEquals(NeedKind.EXPLORE, needs.getLast().kind());
        assertEquals(Executor.DIRECTOR, needs.getLast().executor());
    }

    @Test
    void unloadOnCooldownIsSkipped() {
        Facts facts = Facts.base().freeSlots(2).storage("storage", 30).storageFreeSlots(27);
        FreeTime cooling = new FreeTime(true, FreeTime.Origin.COMMAND, "", 0, null, false, 1, List.of(), List.of(),
                Map.of(NeedKind.UNLOAD, Facts.NOW + 1), List.of());
        assertFalse(kinds(NeedsAssessor.assess(facts.build(), cooling, S)).contains(NeedKind.UNLOAD));
        FreeTime expired = new FreeTime(true, FreeTime.Origin.COMMAND, "", 0, null, false, 1, List.of(), List.of(),
                Map.of(NeedKind.UNLOAD, Facts.NOW - 1), List.of());
        assertTrue(kinds(NeedsAssessor.assess(facts.build(), expired, S)).contains(NeedKind.UNLOAD));
    }

    @Test
    void placesInOtherDimensionAreIgnored() {
        assertFalse(kinds(needs(Facts.base().freeSlots(2).storage("storage", 30).otherDimension()
                .storageFreeSlots(27))).contains(NeedKind.UNLOAD));
        assertFalse(kinds(needs(Facts.base().dark().home("home", 40).otherDimension())).contains(NeedKind.SHELTER));
        assertFalse(kinds(needs(Facts.base().farm("f", "wheat", 5, 3_600_000).otherDimension()))
                .contains(NeedKind.FARM));
    }
}
