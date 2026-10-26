package dev.friendai.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class StructurePlannerTest {
    private static final StructureBook BOOK = StructureBook.withBuiltins();

    private static SpecialStructure s(String id) {
        return BOOK.get(id).orElseThrow();
    }

    /** A free, unprotected overworld site with an empty bag and room for four spawned mobs. */
    private static StructureSnapshot fresh() {
        return new StructureSnapshot(true, false, false, false, Set.of(), false, 0, 4, Map.of());
    }

    private static StructureSnapshot with(StructureSnapshot b, Boolean footprintFree, Boolean entity,
            Boolean prot, Boolean inEnd, Set<Offset> correct, Boolean present, Integer nearby,
            Map<String, Integer> bag) {
        return new StructureSnapshot(
                footprintFree != null ? footprintFree : b.footprintFree(),
                entity != null ? entity : b.entityInFootprint(),
                prot != null ? prot : b.protectedArea(),
                inEnd != null ? inEnd : b.inEnd(),
                correct != null ? correct : b.correctBodyBlocks(),
                present != null ? present : b.outcomeAlreadyPresent(),
                nearby != null ? nearby : b.nearbySpawned(),
                b.maxNearbySpawned(),
                bag != null ? bag : b.bag());
    }

    private static Set<Offset> bodyOffsets(SpecialStructure s) {
        return s.body().blocks().stream().map(b -> new Offset(b.x(), b.y(), b.z())).collect(Collectors.toSet());
    }

    private static StructurePlan.Reason blockedReason(StructurePlan plan) {
        return assertInstanceOf(StructurePlan.Blocked.class, plan).reason();
    }

    @Test
    void freshSiteGivesClearBuildFinishVerifyInOrder() {
        SpecialStructure snow = s("snow_golem");
        StructurePlan.Ready plan = assertInstanceOf(StructurePlan.Ready.class,
                StructurePlanner.plan(snow, fresh()));
        assertEquals(List.of(
                new StructureStep.Clear(),
                new StructureStep.BuildBody(bodyOffsets(snow)),
                new StructureStep.Finish(snow.trigger()),
                new StructureStep.Verify(snow.outcome())), plan.steps());
    }

    @Test
    void protectedAreaIsBlockedFirst() {
        StructureSnapshot snap = with(fresh(), false, true, true, true, null, null, 9, null);
        assertEquals(StructurePlan.Reason.PROTECTED, blockedReason(StructurePlanner.plan(s("nether_portal"), snap)));
    }

    @Test
    void entityInFootprintIsBlocked() {
        StructureSnapshot snap = with(fresh(), null, true, null, null, null, null, null, null);
        assertEquals(StructurePlan.Reason.ENTITY_IN_WAY, blockedReason(StructurePlanner.plan(s("iron_golem"), snap)));
    }

    @Test
    void endBlocksOnlyDisallowedStructures() {
        StructureSnapshot snap = with(fresh(), null, null, null, true, null, null, null,
                Map.of("minecraft:flint_and_steel", 1));
        assertEquals(StructurePlan.Reason.WRONG_DIMENSION, blockedReason(StructurePlanner.plan(s("nether_portal"), snap)));
        assertInstanceOf(StructurePlan.Ready.class, StructurePlanner.plan(s("iron_golem"), snap));
    }

    @Test
    void missingFinishingItemIsBlockedAndNamed() {
        StructurePlan plan = StructurePlanner.plan(s("nether_portal"), fresh());
        assertEquals(StructurePlan.Reason.MISSING_ITEM, blockedReason(plan));
        assertTrue(plan.describe().contains("minecraft:flint_and_steel"), plan.describe());
    }

    @Test
    void presentFinishingItemLetsThePortalPlanProceed() {
        StructureSnapshot snap = with(fresh(), null, null, null, null, null, null, null,
                Map.of("minecraft:flint_and_steel", 1));
        assertInstanceOf(StructurePlan.Ready.class, StructurePlanner.plan(s("nether_portal"), snap));
    }

    @Test
    void limitReachedIsBlockedForSpawnedMobsOnly() {
        StructureSnapshot snap = with(fresh(), null, null, null, null, null, null, 4,
                Map.of("minecraft:flint_and_steel", 1));
        assertEquals(StructurePlan.Reason.LIMIT_REACHED, blockedReason(StructurePlanner.plan(s("iron_golem"), snap)));
        assertInstanceOf(StructurePlan.Ready.class, StructurePlanner.plan(s("nether_portal"), snap));
    }

    @Test
    void occupiedFootprintWithoutRoomIsBlocked() {
        StructureSnapshot snap = with(fresh(), false, null, null, null, null, null, null, null);
        assertEquals(StructurePlan.Reason.NO_ROOM, blockedReason(StructurePlanner.plan(s("snow_golem"), snap)));
    }

    @Test
    void existingBodyIsSkipped() {
        SpecialStructure iron = s("iron_golem");
        StructureSnapshot all = with(fresh(), null, null, null, null, bodyOffsets(iron), null, null, null);
        StructurePlan.Ready ready = assertInstanceOf(StructurePlan.Ready.class, StructurePlanner.plan(iron, all));
        assertEquals(List.of(new StructureStep.Finish(iron.trigger()), new StructureStep.Verify(iron.outcome())),
                ready.steps());

        Set<Offset> partial = new HashSet<>(bodyOffsets(iron));
        partial.remove(new Offset(2, 1, 0));
        StructureSnapshot some = with(fresh(), null, null, null, null, partial, null, null, null);
        StructurePlan.Ready rest = assertInstanceOf(StructurePlan.Ready.class, StructurePlanner.plan(iron, some));
        assertEquals(new StructureStep.BuildBody(Set.of(new Offset(2, 1, 0))), rest.steps().get(1));
    }

    @Test
    void finishedStructureIsDone() {
        StructureSnapshot snap = with(fresh(), null, null, null, null, null, true, null, null);
        assertInstanceOf(StructurePlan.Done.class, StructurePlanner.plan(s("nether_portal"), snap));
    }

    @Test
    void describeSummarisesAReadyPlan() {
        String line = StructurePlanner.plan(s("snow_golem"), fresh()).describe();
        assertTrue(line.contains("snow_golem") && line.contains("2"), line);
    }
}
