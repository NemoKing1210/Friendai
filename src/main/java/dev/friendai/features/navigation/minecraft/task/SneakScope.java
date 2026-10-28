package dev.friendai.features.navigation.minecraft.task;

import dev.friendai.task.TaskContext;

/** Crouches for the length of one task and puts the previous posture back afterwards. */
final class SneakScope {
    private final boolean wanted;
    private boolean previous;
    private boolean entered;

    SneakScope(boolean wanted) {
        this.wanted = wanted;
    }

    void enter(TaskContext c) {
        if (!wanted || entered) {
            return;
        }
        entered = true;
        previous = c.companion().body().sneaking();
        c.companion().body().setSneaking(true, 0);
    }

    void exit(TaskContext c) {
        if (!wanted || !entered) {
            return;
        }
        entered = false;
        c.companion().body().setSneaking(previous, 0);
    }
}
