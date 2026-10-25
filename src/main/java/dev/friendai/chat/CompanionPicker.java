package dev.friendai.chat;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Decides which companion hears a message. Pure: the caller supplies positions, dimensions and owner
 * presence. Order from the design: the sender's own companion within hearing, then the nearest other
 * companion within hearing whose owner is online. A companion that cannot hear the sender never answers.
 */
public final class CompanionPicker {
    private CompanionPicker() {}

    /** One loaded companion, seen from the sender's point of view. */
    public record Candidate(UUID owner, boolean sameDimension, double distance, boolean ownerOnline) {}

    public static Optional<UUID> pick(
            UUID sender, List<Candidate> companions, double hearingRadius, boolean guestsEnabled) {
        boolean ownNearby = companions.stream()
                .anyMatch(c -> c.owner().equals(sender) && c.sameDimension() && c.distance() <= hearingRadius);
        if (ownNearby) {
            return Optional.of(sender);
        }
        if (guestsEnabled) {
            Optional<Candidate> nearest = companions.stream()
                    .filter(c -> !c.owner().equals(sender))
                    .filter(Candidate::sameDimension)
                    .filter(c -> c.ownerOnline())
                    .filter(c -> c.distance() <= hearingRadius)
                    .min(Comparator.comparingDouble(Candidate::distance).thenComparing(Candidate::owner));
            if (nearest.isPresent()) {
                return Optional.of(nearest.get().owner());
            }
        }
        return Optional.empty();
    }
}
