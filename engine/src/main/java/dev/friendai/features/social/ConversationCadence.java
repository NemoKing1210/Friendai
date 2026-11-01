package dev.friendai.features.social;

/**
 * The pace of unprompted lines: a per-scene gap with a little jitter, a quiet window after the owner speaks, a
 * silence while one question waits for an answer, and a backoff when a line drew no answer. Pure and cheap.
 */
public final class ConversationCadence {
    public enum Scene {
        BUSY,
        FREE,
        REST,
        COMBAT,
        AWAY
    }

    // The item's numbers: a calm companion speaks about every two minutes, never closer than a minute and a half.
    private static final int[] BASE_GAP = {3_600, 2_400, 4_800, 200, 6_000};
    private static final int[] JITTER = {600, 400, 800, 80, 1_000};
    private static final int QUIET_AFTER_OWNER = 100;
    private static final int MAX_BACKOFF = 3;

    private int nextAllowedAt;
    private int lastOwnerAt = Integer.MIN_VALUE / 2;
    private int backoff;
    private boolean awaitingAnswer;

    public boolean maySpeak(Scene scene, int tick) {
        if (tick < nextAllowedAt || tick < lastOwnerAt + QUIET_AFTER_OWNER) {
            return false;
        }
        return !(scene == Scene.COMBAT && backoff > 0);
    }

    public boolean mayAskQuestion() {
        return !awaitingAnswer;
    }

    public void ownerSpoke(int tick) {
        lastOwnerAt = tick;
        awaitingAnswer = false;
    }

    public void recordLine(Scene scene, int tick, boolean question, boolean gotAnswer) {
        int gap = BASE_GAP[scene.ordinal()] + jitter(scene, tick);
        if (backoff > 0) {
            gap *= 1 << backoff;
        }
        nextAllowedAt = tick + gap;
        awaitingAnswer = question && !gotAnswer;
        backoff = gotAnswer || !question ? 0 : Math.min(MAX_BACKOFF, backoff + 1);
    }

    public int backoff() {
        return backoff;
    }

    public boolean awaitingAnswer() {
        return awaitingAnswer;
    }

    private static int jitter(Scene scene, int tick) {
        int spread = JITTER[scene.ordinal()];
        return Math.floorMod(tick * 31 + scene.ordinal() * 17, spread + 1);
    }
}
