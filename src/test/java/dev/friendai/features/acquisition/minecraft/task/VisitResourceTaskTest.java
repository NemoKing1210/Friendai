package dev.friendai.features.acquisition.minecraft.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.perception.resource.ResourceCandidates;
import org.junit.jupiter.api.Test;

class VisitResourceTaskTest {
    @Test
    void onlyAnInspectedSiteCanProveItselfEmpty() {
        assertTrue(VisitResourceTask.provesSiteEmpty(true, ResourceCandidates.Verification.OCCLUDED));
        assertFalse(VisitResourceTask.provesSiteEmpty(true, ResourceCandidates.Verification.PRESENT));
        assertFalse(VisitResourceTask.provesSiteEmpty(false, ResourceCandidates.Verification.OCCLUDED));
        assertFalse(VisitResourceTask.provesSiteEmpty(false, ResourceCandidates.Verification.ABSENT));
        assertFalse(VisitResourceTask.provesSiteEmpty(false, ResourceCandidates.Verification.UNLOADED));
    }
}
