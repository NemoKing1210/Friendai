package dev.friendai.features.acquisition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.execution.settings.ResourceSearchSettings;
import dev.friendai.features.knowledge.search.EscortPolicy;
import org.junit.jupiter.api.Test;

class SearchScopeTest {
    private static ResourceSearchSettings settings(boolean allowExpeditions, boolean chunkLoading) {
        return new ResourceSearchSettings(
                allowExpeditions, chunkLoading, 64, 600, 512, 12_000, EscortPolicy.Config.DEFAULTS);
    }

    @Test
    void anExpeditionNeedsAnOwnerAnEnabledServerAndChunkLoading() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SearchScope.resolve(SearchScope.EXPEDITION, false, settings(true, true)));
        assertThrows(
                IllegalArgumentException.class,
                () -> SearchScope.resolve(SearchScope.EXPEDITION, true, settings(false, true)));
        assertThrows(
                IllegalArgumentException.class,
                () -> SearchScope.resolve(SearchScope.EXPEDITION, true, settings(true, false)));

        SearchScope granted = SearchScope.resolve(SearchScope.EXPEDITION, true, settings(true, true));
        assertTrue(granted.expedition());
        assertEquals(SearchScope.EXPEDITION, granted.token());
        assertEquals(512, granted.radius());
        assertEquals(12_000, granted.ticks());
    }

    @Test
    void theLocalScopeIsTheDefaultAndTakesTheLocalBudget() {
        for (String requested : new String[] {null, "", "  ", SearchScope.LOCAL, "LOCAL"}) {
            SearchScope scope = SearchScope.resolve(requested, false, settings(false, false));
            assertEquals(SearchScope.Kind.LOCAL, scope.kind(), "requested: " + requested);
            assertEquals(64, scope.radius());
            assertEquals(600, scope.ticks());
        }
    }

    @Test
    void anUnknownScopeIsRefusedInWordsTheCallerCanActOn() {
        IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class, () -> SearchScope.resolve("wilderness", true, settings(true, true)));
        assertEquals("scope must be local or expedition", refused.getMessage());
    }
}
