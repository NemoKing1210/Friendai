package dev.friendai.features.conversation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pure recipient decision shared by chat lines and speech views. The caller supplies the facts about each
 * candidate; this class never reads a world.
 */
public final class AudiencePolicy {
    private AudiencePolicy() {}

    /** One player seen from the companion: enough facts for the channel decision and nothing more. */
    public record Candidate(UUID player, boolean sameDimension, double distance) {}

    /**
     * Whether a line with this audience reaches the candidate.
     *
     * @param audience the intended audience of the line
     * @param viewer the candidate recipient
     * @param speaker the player whose turn produced the line; ignored for public and nearby lines
     * @param sameDimension whether the viewer is in the companion's dimension
     * @param distance the viewer's distance from the companion, in blocks
     * @param hearingRadius how far the companion's voice carries
     */
    public static boolean reaches(
            Audience audience,
            UUID viewer,
            UUID speaker,
            boolean sameDimension,
            double distance,
            double hearingRadius) {
        if (audience == null || viewer == null) {
            return false;
        }
        return switch (audience) {
            case PUBLIC -> true;
            case PRIVATE -> viewer.equals(speaker);
            case NEARBY -> sameDimension && distance <= hearingRadius;
        };
    }

    /** The players a line reaches, in the caller's order. The adapter collects candidates, it does not decide. */
    public static List<UUID> recipients(
            Audience audience, UUID speaker, List<Candidate> candidates, double hearingRadius) {
        List<UUID> result = new ArrayList<>();
        if (candidates == null) {
            return result;
        }
        for (Candidate candidate : candidates) {
            if (candidate != null
                    && reaches(
                            audience,
                            candidate.player(),
                            speaker,
                            candidate.sameDimension(),
                            candidate.distance(),
                            hearingRadius)) {
                result.add(candidate.player());
            }
        }
        return result;
    }
}
