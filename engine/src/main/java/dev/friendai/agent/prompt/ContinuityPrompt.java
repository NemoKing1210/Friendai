package dev.friendai.agent.prompt;

import dev.friendai.features.memory.Category;
import dev.friendai.features.memory.Fact;
import dev.friendai.features.social.Happening;
import dev.friendai.features.social.HappeningJournal;
import dev.friendai.foundation.places.Place;
import java.util.ArrayList;
import java.util.List;

/**
 * One short block of verified shared history for the system prompt: a milestone, the active project, a known place,
 * the last verified outcome and a remembered preference. At most {@value #MAX_ITEMS} of them are kept, the whole
 * block is a heading plus one line per item, and a source with no data contributes nothing.
 *
 * <p>Nothing here is invented: every item restates a fact that already exists in the owner's records (the happening
 * journal, the goal board, the places book, the session's verified outcomes, the memory book). The heading says the
 * block is background rather than a script and that an item is only mentioned when it fits, so a stale milestone or
 * a place the player has moved on from never turns into a claim.
 */
public final class ContinuityPrompt {

    /** The heading plus at most three item lines: four lines in all. */
    public static final int MAX_ITEMS = 3;

    /** Hard cap on the whole block, so it can never crowd the rest of the prompt. */
    public static final int MAX_CHARS = 400;

    /** One item stays a single readable line; a longer fact is cut, never summarised. */
    static final int MAX_ITEM_CHARS = 96;

    /** Background, not a script: the model decides whether a fact fits the turn. */
    static final String HEADER =
            "Background from your past with your owner, not a script; mention an item only when it fits.";

    private static final String ITEM_PREFIX = "- ";

    private ContinuityPrompt() {}

    /**
     * The block for one turn, or empty when no source has a fact to offer. The journal, places and facts are read
     * without copying beyond what the caller already holds, so an owner turn pays one short pass over each.
     */
    public static String render(
            HappeningJournal journal,
            String activeGoal,
            List<Place> places,
            List<String> verifiedFacts,
            List<Fact> preferences) {
        List<String> items = new ArrayList<>(MAX_ITEMS);
        add(items, milestone(journal));
        add(items, project(activeGoal));
        add(items, place(places));
        add(items, verified(verifiedFacts));
        add(items, preference(preferences));
        return block(items);
    }

    /** The newest achievement on record; deaths, arrivals and respawns are not milestones and are never chosen. */
    private static String milestone(HappeningJournal journal) {
        if (journal == null) {
            return "";
        }
        for (Happening happening : journal.recent(HappeningJournal.MAX_ENTRIES)) {
            if (happening != null
                    && happening.kind() == Happening.Kind.ADVANCEMENT
                    && happening.text() != null
                    && !happening.text().isBlank()) {
                return happening.text();
            }
        }
        return "";
    }

    /** The goal board's primary goal, in the owner's or the model's own words. */
    private static String project(String activeGoal) {
        return activeGoal == null || activeGoal.isBlank() ? "" : "the active project: " + activeGoal;
    }

    /** The place visited most recently, then the one updated most recently; the book order breaks an exact tie. */
    private static String place(List<Place> places) {
        if (places == null) {
            return "";
        }
        Place best = null;
        for (Place candidate : places) {
            if (candidate == null
                    || candidate.name() == null
                    || candidate.name().isBlank()) {
                continue;
            }
            if (best == null
                    || candidate.lastVisitTick() > best.lastVisitTick()
                    || (candidate.lastVisitTick() == best.lastVisitTick()
                            && candidate.updatedTick() > best.updatedTick())) {
                best = candidate;
            }
        }
        return best == null ? "" : "a place you know: " + best.name();
    }

    /** The newest outcome a task actually verified this session. */
    private static String verified(List<String> verifiedFacts) {
        if (verifiedFacts == null) {
            return "";
        }
        for (String fact : verifiedFacts) {
            if (fact != null && !fact.isBlank()) {
                return "last verified: " + fact;
            }
        }
        return "";
    }

    /** The newest remembered preference about the owner; any other category is already in the memory block. */
    private static String preference(List<Fact> preferences) {
        if (preferences == null) {
            return "";
        }
        Fact newest = null;
        for (Fact fact : preferences) {
            if (fact == null
                    || fact.category() != Category.PREFERENCE
                    || fact.text() == null
                    || fact.text().isBlank()) {
                continue;
            }
            if (newest == null || fact.id() > newest.id()) {
                newest = fact;
            }
        }
        return newest == null ? "" : "you remember: " + newest.text();
    }

    private static void add(List<String> items, String item) {
        if (items.size() >= MAX_ITEMS) {
            return;
        }
        String line = oneLine(item);
        if (!line.isEmpty()) {
            items.add(line);
        }
    }

    /** Joins the heading and the items, dropping any item line that would break the character cap. */
    private static String block(List<String> items) {
        StringBuilder text = new StringBuilder(HEADER);
        for (String item : items) {
            if (text.length() + 1 + ITEM_PREFIX.length() + item.length() > MAX_CHARS) {
                break;
            }
            text.append('\n').append(ITEM_PREFIX).append(item);
        }
        return text.length() == HEADER.length() ? "" : text.toString();
    }

    /** One item is always one line: control characters and runs of whitespace collapse, and long text is cut. */
    private static String oneLine(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder text = new StringBuilder(raw.length());
        boolean pendingSpace = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                pendingSpace = text.length() > 0;
                continue;
            }
            if (pendingSpace) {
                text.append(' ');
                pendingSpace = false;
            }
            text.append(c);
        }
        if (text.length() > MAX_ITEM_CHARS) {
            return text.substring(0, MAX_ITEM_CHARS - 3).stripTrailing() + "...";
        }
        return text.toString();
    }
}
