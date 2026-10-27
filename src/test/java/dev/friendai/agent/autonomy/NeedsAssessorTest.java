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
