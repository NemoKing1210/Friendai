package dev.friendai.task.trading;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class MerchantLeasesTest {
    private static final UUID TRADER = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000030");

    @Test void onlyTheOwningOrderCanRenewOrReleaseAndExpiredLeaseCanBeReacquired() {
        var leases = new MerchantLeases();

        assertTrue(leases.acquire(TRADER, FIRST, 10));
        assertFalse(leases.acquire(TRADER, SECOND, 10));
        assertFalse(leases.renew(TRADER, SECOND, 11));
        assertFalse(leases.release(TRADER, SECOND));
        assertTrue(leases.renew(TRADER, FIRST, 11));
        assertTrue(leases.expire(12).isEmpty());
        assertFalse(leases.acquire(TRADER, SECOND, 12));

        var expired = leases.expire(13);
        assertEquals(1, expired.size());
        assertEquals(FIRST, expired.getFirst().orderId());
        assertTrue(leases.acquire(TRADER, SECOND, 13));
    }

    @Test void releaseOnlyRemovesTheMatchingTraderOrderPair() {
        var leases = new MerchantLeases();
        assertTrue(leases.acquire(TRADER, FIRST, 0));

        assertFalse(leases.release(UUID.randomUUID(), FIRST));
        assertFalse(leases.release(TRADER, SECOND));
        assertTrue(leases.release(TRADER, FIRST));
        assertFalse(leases.release(TRADER, FIRST));
        assertTrue(leases.acquire(TRADER, SECOND, 0));
    }

    @Test void timeCannotMoveBackwards() {
        var leases = new MerchantLeases();
        assertFalse(leases.acquire(TRADER, FIRST, -1));
        assertTrue(leases.acquire(TRADER, FIRST, 5));
        assertFalse(leases.acquire(TRADER, FIRST, 4));
        assertFalse(leases.renew(TRADER, FIRST, 4));
        assertTrue(leases.expire(4).isEmpty());
    }
}
