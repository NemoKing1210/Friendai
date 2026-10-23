package dev.friendai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friendai.chat.CompanionPicker.Candidate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CompanionPickerTest {
    private static final UUID S = UUID.fromString("00000000-0000-0000-0000-0000000000f0");
    private static final UUID X = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID Y = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static Candidate c(UUID owner, boolean sameDimension, double distance, boolean online) {
        return new Candidate(owner, sameDimension, distance, online);
    }

    private static Optional<UUID> pick(UUID sender, List<Candidate> companions, double radius, boolean guests) {
        return CompanionPicker.pick(sender, companions, radius, guests);
    }

    @Test
    void ownWithinRadiusBeatsNearerForeign() {
        assertEquals(Optional.of(S), pick(S, List.of(c(S, true, 10, true), c(X, true, 2, true)), 16, true));
    }

    @Test
    void nearestForeignWhenOwnIsFar() {
        assertEquals(Optional.of(X), pick(S, List.of(c(S, true, 40, true), c(X, true, 5, true), c(Y, true, 9, true)), 16, true));
    }

    @Test
    void ownAnywhereWhenNoForeignNearby() {
        assertEquals(Optional.of(S), pick(S, List.of(c(S, false, 999, true), c(X, true, 30, true)), 16, true));
    }

    @Test
    void foreignWithOfflineOwnerIsSkipped() {
        assertEquals(Optional.empty(), pick(S, List.of(c(X, true, 3, false)), 16, true));
    }

    @Test
    void otherDimensionIsNotWithinRadius() {
        assertEquals(Optional.empty(), pick(S, List.of(c(X, false, 3, true)), 16, true));
    }

    @Test
    void guestsDisabledFallsBackToOwn() {
        assertEquals(Optional.of(S), pick(S, List.of(c(S, true, 40, true), c(X, true, 2, true)), 16, false));
        assertEquals(Optional.empty(), pick(S, List.of(c(X, true, 2, true)), 16, false));
    }

    @Test
    void radiusIsInclusive() {
        assertEquals(Optional.of(X), pick(S, List.of(c(X, true, 16, true)), 16, true));
    }

    @Test
    void equalDistanceTieBreaksByOwnerUuid() {
        assertEquals(Optional.of(X), pick(S, List.of(c(Y, true, 4, true), c(X, true, 4, true)), 16, true));
    }
}
