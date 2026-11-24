package dev.friendai.agent.plan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlanReferencePolicyTest {

    @Test
    void aReferenceNothingGroundsMustBeQuestioned() {
        assertTrue(PlanReferencePolicy.unresolved("build the second version there", false, false, false, false));
        assertTrue(PlanReferencePolicy.unresolved("Put it over there", false, false, false, false));
        assertTrue(PlanReferencePolicy.unresolved("make that one", false, false, false, false));
    }

    @Test
    void anySingleSourceOfEvidenceLetsThePlannerResolveIt() {
        String order = "build the second version there";
        assertFalse(PlanReferencePolicy.unresolved(order, true, false, false, false), "dialogue");
        assertFalse(PlanReferencePolicy.unresolved(order, false, true, false, false), "remembered");
        assertFalse(PlanReferencePolicy.unresolved(order, false, false, true, false), "places");
        assertFalse(PlanReferencePolicy.unresolved(order, false, false, false, true), "pointed target");
    }

    @Test
    void anOrdinaryLineIsNeverQuestioned() {
        assertFalse(PlanReferencePolicy.unresolved("bring me 32 oak planks", false, false, false, false));
        assertFalse(PlanReferencePolicy.unresolved("", false, false, false, false));
        assertFalse(PlanReferencePolicy.unresolved(null, false, false, false, false));
    }

    @Test
    void matchingIsCaseAndPunctuationInsensitive() {
        assertTrue(PlanReferencePolicy.hasReference("Build the SECOND version, please."));
        assertTrue(PlanReferencePolicy.hasReference("\"That design\" again"));
        assertFalse(PlanReferencePolicy.hasReference("secondly, gather wood"));
    }

    @Test
    void russianReferencesAreRecognisedTheSameWay() {
        assertTrue(PlanReferencePolicy.unresolved("построй тот же дом", false, false, false, false));
        assertTrue(PlanReferencePolicy.unresolved("иди туда же, там строй", false, false, false, false));
        assertFalse(PlanReferencePolicy.unresolved("построй тот же дом", true, false, false, false));
        assertFalse(PlanReferencePolicy.unresolved("принеси 32 доски", false, false, false, false));
    }
}
