package dev.friendai.features.mining;

public final class BreakProgress {
    private BreakProgress() {}

    /** Vanilla-like break time: a correct tool is 30 ticks per hardness point, a wrong one 100. */
    public static int ticksToBreak(float hardness, float toolSpeed, boolean correctTool) {
        if (hardness == 0f) {
            return 1;
        }
        return (int) Math.ceil(hardness * (correctTool ? 30f : 100f) / Math.max(1f, toolSpeed));
    }
}
