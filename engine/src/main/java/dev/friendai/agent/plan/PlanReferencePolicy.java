package dev.friendai.agent.plan;

import java.util.List;
import java.util.Locale;

/**
 * Decides whether an order points at something the planner cannot resolve from what it is given.
 *
 * <p>A follow-up such as "build the second version there" is meaningful only against the conversation it continues,
 * a remembered place or fact, or what the player is pointing at. When the line carries one of a closed set of
 * reference phrases and none of those evidence sources exists, planning it would invent a target: this policy names
 * the turn as needing a question instead. It is a refusal, never a heuristic guess — any evidence at all lets the
 * planner resolve the reference itself, and an ordinary line without a reference phrase is never questioned.
 *
 * <p>English and Russian phrases are matched case-insensitively as whole words; the list is deliberately short so
 * that an unusual phrasing costs a plan, never a wrong one.
 */
public final class PlanReferencePolicy {
    /** Phrases whose meaning depends on what came before. */
    private static final List<String> REFERENCES = List.of(
            "the second",
            "the third",
            "the last one",
            "the previous",
            "the other one",
            "that design",
            "that version",
            "that place",
            "that spot",
            "that one",
            "this design",
            "this version",
            "same place",
            "same spot",
            "over there",
            // Russian
            "тот же",
            "там же",
            "второй",
            "вторую",
            "третий",
            "третью",
            "прошлый",
            "предыдущий",
            "тот же проект",
            "то место",
            "там");

    private PlanReferencePolicy() {}

    /**
     * Whether this line must be questioned before it is planned.
     *
     * @param playerText what the player said in the turn
     * @param hasDialogue delivered lines of the current conversation are available
     * @param hasRemembered durable owner/world facts are available
     * @param hasPlaces known places are available
     * @param hasPointedTarget the player pointed at a block or entity with the line
     */
    public static boolean unresolved(
            String playerText,
            boolean hasDialogue,
            boolean hasRemembered,
            boolean hasPlaces,
            boolean hasPointedTarget) {
        if (playerText == null || playerText.isBlank()) return false;
        if (!hasReference(playerText)) return false;
        return !hasDialogue && !hasRemembered && !hasPlaces && !hasPointedTarget;
    }

    /** Whether the line contains one of the closed reference phrases as whole words. */
    public static boolean hasReference(String playerText) {
        if (playerText == null || playerText.isBlank()) return false;
        String folded = playerText.toLowerCase(Locale.ROOT).replace('ё', 'е');
        String text = " " + folded.replaceAll("[^\\p{L}\\p{N}]+", " ").strip() + " ";
        for (String phrase : REFERENCES) {
            if (text.contains(" " + phrase + " ")) return true;
        }
        return false;
    }
}
