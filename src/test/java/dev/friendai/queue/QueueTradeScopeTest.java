package dev.friendai.queue;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QueueTradeScopeTest {
    private static final UUID ORIGINAL = UUID.fromString("00000000-0000-0000-0000-000000000123");
    private static final UUID LATER_TURN = UUID.fromString("00000000-0000-0000-0000-000000000456");

    @Test
    void addCapturesOnlyServerScopeAndRejectsForgedValues() {
        JsonObject modelArgs = new JsonObject();
        modelArgs.addProperty("count", 2);
        JsonObject queued = QueueTradeScope.capture(modelArgs, ORIGINAL, true);
        assertEquals(ORIGINAL, QueueTradeScope.read(queued));
        assertFalse(modelArgs.has(QueueTradeScope.ARG));
        assertThrows(IllegalArgumentException.class, () -> QueueTradeScope.capture(queued, LATER_TURN, true));
        assertThrows(IllegalArgumentException.class, () -> QueueTradeScope.capture(modelArgs, null, true));
    }

    @Test
    void deferredEnqueueCapturesScopeAndLimitsSpendingToOneAttempt() {
        JsonObject source = new JsonObject();
        source.addProperty("count", 4);

        var spending = QueueTradeScope.captureEnqueue(source, ORIGINAL, true, 5);
        var ordinary = QueueTradeScope.captureEnqueue(source, ORIGINAL, false, 5);

        assertEquals(ORIGINAL, spending.tradingScope());
        assertEquals(ORIGINAL, QueueTradeScope.read(spending.args()));
        assertEquals(1, spending.maxAttempts());
        assertEquals(5, ordinary.maxAttempts());
        assertNull(ordinary.tradingScope());
        assertFalse(ordinary.args().has(QueueTradeScope.ARG));
    }

    @Test
    void updateAndReplacePreserveOriginalScopeAcrossLaterTurns() {
        JsonObject oldArgs = new JsonObject();
        oldArgs.addProperty(QueueTradeScope.ARG, ORIGINAL.toString());
        JsonObject editedArgs = new JsonObject();
        editedArgs.addProperty("count", 3);

        JsonObject updated = QueueTradeScope.preserve(editedArgs, oldArgs, true);
        JsonObject replaced = QueueTradeScope.preserve(editedArgs, oldArgs, true);

        assertEquals(ORIGINAL, QueueTradeScope.read(updated));
        assertEquals(ORIGINAL, QueueTradeScope.read(replaced));
        assertNotEquals(LATER_TURN, QueueTradeScope.read(updated));
        assertThrows(
                IllegalArgumentException.class, () -> QueueTradeScope.preserve(editedArgs, new JsonObject(), true));
        JsonObject forged = new JsonObject();
        forged.addProperty(QueueTradeScope.ARG, LATER_TURN.toString());
        assertThrows(IllegalArgumentException.class, () -> QueueTradeScope.preserve(forged, oldArgs, true));
    }

    @Test
    void nonspendingReplacementDoesNotCarryAnObsoleteScopeMarker() {
        JsonObject oldArgs = new JsonObject();
        oldArgs.addProperty(QueueTradeScope.ARG, ORIGINAL.toString());
        JsonObject ordinaryArgs = new JsonObject();
        ordinaryArgs.addProperty("item", "minecraft:dirt");

        JsonObject replacement = QueueTradeScope.preserve(ordinaryArgs, oldArgs, false);

        assertFalse(replacement.has(QueueTradeScope.ARG));
    }

    @Test
    void manualRetryUsesOriginalScopeAndRequiresOwnerAccess() {
        JsonObject savedArgs = new JsonObject();
        savedArgs.addProperty(QueueTradeScope.ARG, ORIGINAL.toString());
        assertEquals(ORIGINAL, QueueTradeScope.retry(savedArgs, true, true));
        assertThrows(IllegalArgumentException.class, () -> QueueTradeScope.retry(savedArgs, true, false));
        assertThrows(IllegalArgumentException.class, () -> QueueTradeScope.retry(new JsonObject(), true, true));
    }

    @Test
    void restoreRequiresFreshAuthorityForMarkedOrTraitDeclaredSpendingWork() {
        JsonObject obtainArgs = new JsonObject();
        obtainArgs.addProperty(QueueTradeScope.ARG, ORIGINAL.toString());
        assertTrue(QueueTradeScope.requiresFreshAuthority(obtainArgs, false));
        assertTrue(QueueTradeScope.requiresFreshAuthority(new JsonObject(), true));
        assertFalse(QueueTradeScope.requiresFreshAuthority(new JsonObject(), false));
    }
}
