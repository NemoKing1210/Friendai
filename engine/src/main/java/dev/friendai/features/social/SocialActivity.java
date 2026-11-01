package dev.friendai.features.social;

/**
 * How much a companion said in one window, as plain counters: nothing player-facing, only a way for an operator's
 * debug log to answer whether the companion is silent or noisy. Pure, so it needs no Minecraft and costs an int per
 * event. One instance belongs to one owner and is reset when its window is written out.
 */
public final class SocialActivity {
    private int ordinaryLines;
    private int urgentLines;
    private int heldLines;
    private int droppedLines;
    private int ownerMessages;

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

    /** The unprompted lines that actually reached the brain, urgent and ordinary together. */
    public int totalLines() {
        return ordinaryLines + urgentLines;
    }

    /** One stable line for the debug log: every counter named, no ratio and no invented number. */
    public String summary() {
        return "social activity: " + ordinaryLines + " ordinary, " + urgentLines + " urgent, " + heldLines + " held, "
                + droppedLines + " dropped lines; " + ownerMessages + " owner messages";
    }

    /** Starts a fresh window: every counter returns to zero. */
    public void reset() {
        ordinaryLines = 0;
        urgentLines = 0;
        heldLines = 0;
        droppedLines = 0;
        ownerMessages = 0;
    }
}
