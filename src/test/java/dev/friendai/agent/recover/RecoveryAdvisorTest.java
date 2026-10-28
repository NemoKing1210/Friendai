package dev.friendai.agent.recover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecoveryAdvisorTest {
    private final RecoveryAdvisor advisor = new RecoveryAdvisor();

    @Test
    void missingItemSuggestsObtainItem() {
        Diagnosis d = advisor.diagnose("build_structure", "missing materials: 3 × minecraft:iron_ingot", Set.of());
        assertEquals(FailureKind.MISSING_ITEM, d.kind());
        assertEquals("missing 3 minecraft:iron_ingot", d.detail());
        assertEquals(List.of("obtain_item minecraft:iron_ingot"), d.remedies());
        assertFalse(d.needsPlayer());
    }

    @Test
    void noPathIsUnreachable() {
        Diagnosis d = advisor.diagnose("go_to", "can't reach 10 64 -3", Set.of());
        assertEquals(FailureKind.UNREACHABLE, d.kind());
        assertFalse(d.remedies().isEmpty());
    }

    @Test
    void missingPickaxeIsNoTool() {
        Diagnosis d = advisor.diagnose("farm", "missing hoe", Set.of());
        assertEquals(FailureKind.NO_TOOL, d.kind());
        assertTrue(d.remedies().get(0).contains("hoe"), d.remedies().toString());
    }

    @Test
    void noOreInRadiusIsResourceNotFoundWithSearchRemedy() {
        Diagnosis d = advisor.diagnose("fish", "no open water nearby", Set.of());
        assertEquals(FailureKind.RESOURCE_NOT_FOUND, d.kind());
        assertTrue(d.remedies().stream().anyMatch(r -> r.startsWith("search_resource")), d.remedies().toString());
    }

    @Test
    void unrecognizedTextIsUnknownWithNoRemedies() {
        Diagnosis d = advisor.diagnose("stay", "the moon is in the wrong phase", Set.of());
        assertEquals(FailureKind.UNKNOWN, d.kind());
        assertTrue(d.remedies().isEmpty());
        assertFalse(d.needsPlayer());
    }

    @Test
    void remedyAlreadyTriedIsNotSuggestedAgainAndEscalates() {
        Diagnosis d = advisor.diagnose("build_structure", "missing materials: 3 × minecraft:iron_ingot",
                Set.of("obtain_item minecraft:iron_ingot"));
        assertTrue(d.remedies().isEmpty());
        assertTrue(d.needsPlayer(), "with every remedy tried only the player can help");
    }

    @Test
    void protectedAreaNeedsThePlayer() {
        Diagnosis d = advisor.diagnose("build_structure", "10 64 -3 is protected", Set.of());
        assertEquals(FailureKind.FORBIDDEN, d.kind());
        assertTrue(d.needsPlayer());
    }

    @Test
    void aPlaceRefusalTellsTheModelWhereToMineInstead() {
        Diagnosis d = advisor.diagnose("mine_blocks",
                "24 68 17 is inside the house place \"home\" (r=32)", Set.of());
        assertEquals(FailureKind.RESOURCE_NOT_FOUND, d.kind());
        assertEquals(List.of("mine outside home"), d.remedies());
        assertFalse(d.needsPlayer(), "a place it can leave is not an owner decision");
    }

    @Test
    void serverProtectionComesWithAWayOut() {
        Diagnosis d = advisor.diagnose("mine_block_at",
                "24 68 17 is protected by the server (spawn protection or a world rule)", Set.of());
        assertEquals(FailureKind.FORBIDDEN, d.kind());
        assertFalse(d.remedies().isEmpty());
        assertFalse(d.needsPlayer(), "the model must try the nearest legal block first");
    }

    @Test
    void theBlockUnderTheFeetSuggestsANeighbour() {
        Diagnosis d = advisor.diagnose("mine_block_at",
                "24 68 17 is the block I am standing on", Set.of());
        assertEquals(FailureKind.UNREACHABLE, d.kind());
        assertTrue(d.remedies().contains("mine a neighbouring block instead"), d.remedies().toString());
        assertFalse(d.needsPlayer());
    }

    @Test
    void theBreakListAndAnUnbreakableBlockHaveTheirOwnAdvice() {
        Diagnosis listed = advisor.diagnose("mine_blocks", "24 68 17 is on the break list (minecraft:stone)",
                Set.of());
        assertEquals(FailureKind.FORBIDDEN, listed.kind());
        assertTrue(listed.remedies().getFirst().contains("not on the break list"), listed.remedies().toString());
        assertFalse(listed.needsPlayer());

        Diagnosis bedrock = advisor.diagnose("mine_block_at",
                "minecraft:bedrock at 24 68 17 cannot be broken (minecraft:bedrock)", Set.of());
        assertTrue(bedrock.remedies().contains("choose another block"), bedrock.remedies().toString());
        assertFalse(bedrock.needsPlayer());
    }

    @Test
    void nullAndEmptyTextAreUnknown() {
        assertEquals(FailureKind.UNKNOWN, advisor.diagnose("x", null, Set.of()).kind());
        assertEquals(FailureKind.UNKNOWN, advisor.diagnose("x", "", Set.of()).kind());
    }

    @Test
    void diagnosisLineIsReadable() {
        Diagnosis d = advisor.diagnose("build_structure", "missing materials: 3 × minecraft:iron_ingot", Set.of());
        assertEquals("diagnosis: MISSING_ITEM missing 3 minecraft:iron_ingot; try: obtain_item minecraft:iron_ingot", d.line());
    }
}
