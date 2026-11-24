package dev.friendai.features.acquisition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** How one material need is ranked and split across the candidate items that can satisfy it. */
class NeedAllocationTest {
    private static final String COBBLESTONE = "minecraft:cobblestone";
    private static final String COBBLED_DEEPSLATE = "minecraft:cobbled_deepslate";
    private static final String BLACKSTONE = "minecraft:blackstone";
    private static final String STONE = "minecraft:stone";

    private static ObtainPlan complete(String item, int count) {
        return new ObtainPlan(
                List.of(new ObtainStep.Mine(
                        STONE, count, new ToolNeed(ToolKind.PICKAXE, dev.friendai.foundation.item.Tier.WOOD))),
                Map.of(),
                count,
                false,
                new ObtainPlan.Work(1, 1),
                false,
                count);
    }

    private static ObtainPlan partial(String target, int count, int supplied) {
        return new ObtainPlan(
                List.of(new ObtainStep.Take(target, supplied)),
                Map.of(target, count - supplied),
                supplied,
                false,
                new ObtainPlan.Work(1, 1),
                false,
                supplied);
    }

    private static ObtainPlan remembered(int count) {
        return new ObtainPlan(
                List.of(new ObtainStep.VisitResource(7, "minecraft:overworld", STONE)),
                Map.of(),
                count,
                false,
                new ObtainPlan.Work(1, 1),
                false,
                count);
    }

    private static ObtainPlan exploring(String family) {
        return new ObtainPlan(
                List.of(new ObtainStep.Explore(family, false, 0)),
                Map.of(),
                1,
                false,
                new ObtainPlan.Work(1, 1),
                false,
                1);
    }

    private static ObtainPlan nothing() {
        return new ObtainPlan(List.of(), Map.of(STONE, 1), 0);
    }

    @Test
    void localSupplyBeatsARememberedSiteWhichBeatsExploration() {
        List<NeedAllocation.Leg> legs = NeedAllocation.allocate(
                1,
                List.of(
                        new NeedAllocation.Candidate(BLACKSTONE, exploring("#minecraft:stone_tool_materials"), 0),
                        new NeedAllocation.Candidate(COBBLED_DEEPSLATE, remembered(1), 1),
                        new NeedAllocation.Candidate(COBBLESTONE, complete(COBBLESTONE, 1), 2)));

        assertEquals(List.of(new NeedAllocation.Leg(COBBLESTONE, 1)), legs);
    }

    @Test
    void aPartialPlanContributesOnlyThePartItCanSupply() {
        List<NeedAllocation.Leg> legs = NeedAllocation.allocate(
                12,
                List.of(
                        new NeedAllocation.Candidate(COBBLESTONE, partial(COBBLESTONE, 12, 5), 0),
                        new NeedAllocation.Candidate(BLACKSTONE, complete(BLACKSTONE, 12), 1)));

        assertEquals(
                List.of(new NeedAllocation.Leg(BLACKSTONE, 12)),
                legs,
                "a complete plan covers the whole need before a partial one is spent");
    }

    @Test
    void severalPartialSourcesCoverOneNeedInRankedOrder() {
        List<NeedAllocation.Leg> legs = NeedAllocation.allocate(
                12,
                List.of(
                        new NeedAllocation.Candidate(COBBLESTONE, partial(COBBLESTONE, 12, 5), 0),
                        new NeedAllocation.Candidate(COBBLED_DEEPSLATE, partial(COBBLED_DEEPSLATE, 12, 7), 1),
                        new NeedAllocation.Candidate(BLACKSTONE, partial(BLACKSTONE, 12, 4), 2)));

        assertEquals(
                List.of(new NeedAllocation.Leg(COBBLED_DEEPSLATE, 7), new NeedAllocation.Leg(COBBLESTONE, 5)),
                legs,
                "the need is split across the sources with the most supply first");
    }

    @Test
    void theWholeNeedFromOneSourceIsNotSplit() {
        List<NeedAllocation.Leg> legs = NeedAllocation.allocate(
                4,
                List.of(
                        new NeedAllocation.Candidate(COBBLESTONE, complete(COBBLESTONE, 4), 0),
                        new NeedAllocation.Candidate(BLACKSTONE, partial(BLACKSTONE, 4, 4), 1)));

        assertEquals(List.of(new NeedAllocation.Leg(COBBLESTONE, 4)), legs);
    }

    @Test
    void aCandidateWithNoSourceSuppliesNothing() {
        List<NeedAllocation.Leg> legs = NeedAllocation.allocate(
                2,
                List.of(
                        new NeedAllocation.Candidate(COBBLESTONE, nothing(), 0),
                        new NeedAllocation.Candidate(BLACKSTONE, partial(BLACKSTONE, 2, 1), 1)));

        assertEquals(List.of(new NeedAllocation.Leg(BLACKSTONE, 1)), legs);
    }

    @Test
    void aWorkLimitedPassIsNotEvidenceOfSupplyOrAbsence() {
        ObtainPlan limited = new ObtainPlan(
                List.of(), Map.of(COBBLESTONE, 2), 0, false, new ObtainPlan.Work(20_000, 40_000), true, 0);

        List<NeedAllocation.Leg> legs = NeedAllocation.allocate(
                2,
                List.of(
                        new NeedAllocation.Candidate(COBBLESTONE, limited, 0),
                        new NeedAllocation.Candidate(BLACKSTONE, nothing(), 1)));

        assertTrue(legs.isEmpty(), "neither candidate supplies anything: " + legs);
    }

    @Test
    void insufficientTotalStockCoversOnlyWhatExists() {
        List<NeedAllocation.Leg> legs = NeedAllocation.allocate(
                10,
                List.of(
                        new NeedAllocation.Candidate(COBBLESTONE, partial(COBBLESTONE, 10, 3), 0),
                        new NeedAllocation.Candidate(BLACKSTONE, partial(BLACKSTONE, 10, 2), 1)));

        assertEquals(List.of(new NeedAllocation.Leg(COBBLESTONE, 3), new NeedAllocation.Leg(BLACKSTONE, 2)), legs);
    }

    @Test
    void thePreferenceOrderBreaksAFullTie() {
        List<NeedAllocation.Leg> legs = NeedAllocation.allocate(
                1,
                List.of(
                        new NeedAllocation.Candidate(BLACKSTONE, complete(BLACKSTONE, 1), 5),
                        new NeedAllocation.Candidate(COBBLESTONE, complete(COBBLESTONE, 1), 0)));

        assertEquals(List.of(new NeedAllocation.Leg(COBBLESTONE, 1)), legs);
    }

    @Test
    void anExactItemRequestIsNotSpreadAcrossTheFamily() {
        // The caller asks for one concrete item; a need with a single candidate can only ever allocate that item.
        List<NeedAllocation.Leg> legs = NeedAllocation.allocate(
                6, List.of(new NeedAllocation.Candidate(COBBLED_DEEPSLATE, partial(COBBLED_DEEPSLATE, 6, 6), 0)));

        assertEquals(List.of(new NeedAllocation.Leg(COBBLED_DEEPSLATE, 6)), legs);
    }
}
