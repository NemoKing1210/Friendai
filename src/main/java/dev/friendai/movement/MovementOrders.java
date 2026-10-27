package dev.friendai.movement;

import java.util.Set;

/**
 * The orders that move the companion without being work: an explicit come-here, a wait in place, a walk to a point.
 *
 * <p>They matter to free time, which reads the task runner to decide whether the owner's work is in the way. A
 * movement order is not: the owner told the companion where to be, not what to do, so the agenda waits for it but the
 * shift it interrupted is kept, and no fallback replaces it. A movement order also ends as soon as queued work is
 * ready, so it can never hold the queue.
 */
public final class MovementOrders {
    /** Tool names of the movement orders, as registered in {@code MovementTools}. */
    public static final Set<String> TOOLS = Set.of("stay", "follow_player", "go_to");

    private MovementOrders() {}

    public static boolean isOrder(String toolName) {
        return toolName != null && TOOLS.contains(toolName);
    }
}
