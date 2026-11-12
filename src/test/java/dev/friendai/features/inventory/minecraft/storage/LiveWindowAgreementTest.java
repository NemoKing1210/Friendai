package dev.friendai.features.inventory.minecraft.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friendai.features.knowledge.ContainerFreshness;
import dev.friendai.features.storage.StorageLimits;
import org.junit.jupiter.api.Test;

/** The storage text and the knowledge book must agree on how long a container stays in view. */
class LiveWindowAgreementTest {
    @Test
    void storageAndKnowledgeShareOneLiveWindow() {
        assertEquals(ContainerFreshness.LIVE_TICKS, StorageLimits.LIVE_TICKS);
        assertEquals(ContainerFreshness.REMEMBER_TICKS, StorageLimits.DEFAULTS.staleAfterTicks());
    }
}
