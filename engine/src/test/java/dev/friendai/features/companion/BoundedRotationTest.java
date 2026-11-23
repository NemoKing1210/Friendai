package dev.friendai.features.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BoundedRotationTest {
    @Test
    void neverVisitsMoreThanTheAllowance() {
        BoundedRotation rotation = new BoundedRotation(8);
        Set<UUID> keys = new HashSet<>();
        for (int index = 0; index < 40; index++) {
            keys.add(UUID.randomUUID());
        }
        for (int pass = 0; pass < 7; pass++) {
            List<UUID> batch = rotation.next(keys);
            assertTrue(batch.size() <= 8, "a pass must stay within the allowance");
            assertTrue(keys.containsAll(batch), "a pass must only name keys that exist");
        }
    }

    @Test
    void drainsEveryKeyInStablePassesAndWraps() {
        BoundedRotation rotation = new BoundedRotation(3);
        Set<UUID> keys = new HashSet<>();
        for (int index = 0; index < 7; index++) {
            keys.add(UUID.randomUUID());
        }
        Set<UUID> seen = new HashSet<>();
        for (int pass = 0; pass < 7; pass++) {
            List<UUID> batch = rotation.next(keys);
            seen.addAll(batch);
        }
        assertEquals(keys, seen, "every key must be visited once the passes wrap");
        // A shrinking set keeps making progress instead of revisiting the same low-order keys.
        for (UUID key : keys) {
            rotation.next(Set.of(key));
        }
    }

    @Test
    void continuesAfterTheLastVisitedKeyAtEqualSize() {
        BoundedRotation rotation = new BoundedRotation(2);
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID third = UUID.fromString("00000000-0000-0000-0000-000000000003");
        Set<UUID> keys = Set.of(first, second, third);
        assertEquals(List.of(first, second), rotation.next(keys));
        assertEquals(List.of(third), rotation.next(keys));
        assertEquals(List.of(first, second), rotation.next(keys));
    }

    @Test
    void doesNotVisitAKeyTwiceInOnePass() {
        BoundedRotation rotation = new BoundedRotation(5);
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        assertEquals(List.of(first, second), rotation.next(Set.of(first, second)));
        assertEquals(List.of(first, second), rotation.next(Set.of(first, second)));
    }

    @Test
    void rebuildsTheOrderWhenMembershipChangesAtTheSameSize() {
        BoundedRotation rotation = new BoundedRotation(2);
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID third = UUID.fromString("00000000-0000-0000-0000-000000000003");
        assertEquals(List.of(first, second), rotation.next(Set.of(first, second)));
        rotation.invalidateMembership();
        assertEquals(
                List.of(third),
                rotation.next(Set.of(second, third)),
                "a same-size membership change must not leave a removed key in the cached order");
    }

    @Test
    void detectsASameSizeSwapWithoutAnExplicitHint() {
        BoundedRotation rotation = new BoundedRotation(2);
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID third = UUID.fromString("00000000-0000-0000-0000-000000000003");
        assertEquals(List.of(first, second), rotation.next(Set.of(first, second)));
        assertEquals(
                List.of(third),
                rotation.next(Set.of(second, third)),
                "a same-size input swap must rebuild the order from the current keys");
    }

    @Test
    void rejectsANonPositiveAllowance() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedRotation(0));
        assertThrows(IllegalArgumentException.class, () -> new BoundedRotation(-1));
    }
}
