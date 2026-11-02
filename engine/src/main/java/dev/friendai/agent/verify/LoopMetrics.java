package dev.friendai.agent.verify;

/**
 * What the agent loop decided in one window, as plain int counters: nothing player-facing, only a way for an
 * operator's debug log to answer whether goal claims are being checked, whether work is being repeated, and how long
 * an owner's stop takes to clear. Pure, so it needs no Minecraft and costs an int per event. One instance belongs to
 * one companion's execution coordinator and is reset when its window is written out.
 *
 * <p>A done goal ends in one of three ways and each is counted: the world confirmed the criteria
 * ({@link #goalVerified()}), the goal had nothing the world could check ({@link #goalUnverified()}), or the model's
 * completion claim was refused because a criterion did not hold ({@link #completionRefused()}). The other counters
 * are refusals and interruptions, not outcomes.
 */
public final class LoopMetrics {
    private int verifiedGoals;
    private int unverifiedGoals;
    private int completionsRefused;
    private int duplicateRefusals;
    private int interrupts;
    private int interruptsCleared;
    private int maxInterruptTicks;
    private int totalInterruptTicks;
    private int repeatedRemedies;
    private int clarifications;

    /** A done goal whose checkable criteria the world confirmed. */
    public void goalVerified() {
        verifiedGoals++;
    }

    /** A done goal the world could not check at all, so the model's word ended it. */
    public void goalUnverified() {
        unverifiedGoals++;
    }

    /** A done claim the world contradicted: the goal stays active and the model is told what is missing. */
    public void completionRefused() {
        completionsRefused++;
    }

    /** Autonomous work was refused because the queue already held an equivalent step. */
    public void duplicateWorkRefused() {
        duplicateRefusals++;
    }

    /** An interrupt was issued: an owner stop that had work to cancel. Its ticks follow once the queue is idle. */
    public void interruptIssued() {
        interrupts++;
    }

    /** The queue was idle again after an issued interrupt: how long that took, in game ticks. */
    public void interruptCleared(int ticks) {
        int waited = Math.max(0, ticks);
        interruptsCleared++;
        totalInterruptTicks += waited;
        if (waited > maxInterruptTicks) {
            maxInterruptTicks = waited;
        }
    }

    /**
     * A remedy the companion already tried was offered again.
     *
     * <p>TODO(37.12): no single site owns this report yet. {@code RecoveryAdvisor} drops already-tried remedies
     * silently, so wiring this would need that filter to report what it dropped; until then the counter stays zero.
     */
    public void remedyRepeated() {
        repeatedRemedies++;
    }

    /**
     * The companion asked the owner a clarifying question.
     *
     * <p>TODO(37.12): nothing in the loop asks one yet, so no site owns this counter; it stays zero until one does.
     */
    public void clarificationAsked() {
        clarifications++;
    }

    /** Integer average wait per cleared interrupt, or zero when none has cleared: never a float, never a guess. */
    private int averageInterruptTicks() {
        return interruptsCleared == 0 ? 0 : totalInterruptTicks / interruptsCleared;
    }

    /** One stable line for the debug log: every counter named, no ratio and no invented number. */
    public String summary() {
        return "loop: " + verifiedGoals + " verified, " + unverifiedGoals + " unverified goals; "
                + completionsRefused + " completions refused; " + duplicateRefusals + " duplicate refusals; "
                + interrupts + " interrupts (max " + maxInterruptTicks + " ticks, avg " + averageInterruptTicks()
                + "); " + repeatedRemedies + " repeated remedies; " + clarifications + " clarifications";
    }

    /** Starts a fresh window: every counter returns to zero. */
    public void reset() {
        verifiedGoals = 0;
        unverifiedGoals = 0;
        completionsRefused = 0;
        duplicateRefusals = 0;
        interrupts = 0;
        interruptsCleared = 0;
        maxInterruptTicks = 0;
        totalInterruptTicks = 0;
        repeatedRemedies = 0;
        clarifications = 0;
    }
}
