package dev.friendai.features.social;

/**
 * How much a companion said in one window, as plain counters: nothing player-facing, only a way for an operator's
 * debug log to answer whether the companion is silent or noisy. Pure, so it needs no Minecraft and costs an int per
 * event. One instance belongs to one owner and is reset when its window is written out.
 *
 * <p>Five counters describe what the companion did (ordinary, urgent, held and dropped lines, owner messages) and a
 * sixth counts what it had to work with: the happenings the server saw for that owner, so silence can be read as
 * either nothing happening or something happening and not being told.
 */
public final class SocialActivity {
    private int ordinaryLines;
    private int urgentLines;
    private int heldLines;
    private int droppedLines;
    private int ownerMessages;
    private int events;

    /** A line reached the brain: urgent when it carried a happening, ordinary when it opened a subject instead. */
    public void line(boolean urgent) {
        if (urgent) {
            urgentLines++;
        } else {
            ordinaryLines++;
        }
    }

    /** A line nobody could hear was put aside to try again within the wait window. */
    public void held() {
        heldLines++;
    }

    /** A held line ran out of wait ticks and was forgotten, so it will never be spoken. */
    public void dropped() {
        droppedLines++;
    }

    /** The owner spoke to the companion. */
    public void ownerSpoke() {
        ownerMessages++;
    }

    /** The server saw a happening for this owner: one journal entry's worth, whatever the companion then says. */
    public void event() {
        events++;
    }

    /** The unprompted lines that actually reached the brain, urgent and ordinary together; events are not lines. */
    public int totalLines() {
        return ordinaryLines + urgentLines;
    }

    /** The happenings the server saw for this owner, the pool every spoken happening comes from. */
    public int events() {
        return events;
    }

    /** One stable line for the debug log: every counter named, no ratio and no invented number. */
    public String summary() {
        return "social activity: " + ordinaryLines + " ordinary, " + urgentLines + " urgent, " + heldLines + " held, "
                + droppedLines + " dropped lines; " + ownerMessages + " owner messages; " + events + " events seen";
    }

    /** Starts a fresh window: every counter returns to zero. */
    public void reset() {
        ordinaryLines = 0;
        urgentLines = 0;
        heldLines = 0;
        droppedLines = 0;
        ownerMessages = 0;
        events = 0;
    }
}
