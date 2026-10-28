package dev.friendai.task.builtin;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.movement.Emotes;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;

/** Plays a short scripted gesture: a wave, a nod, a bow. Leaves the posture as it found it. */
public final class EmoteTask implements Task {
    private final Emotes.Kind kind;
    private final boolean quiet;
    private int tick;
    private float baseYaw;
    private boolean sneakBefore;
    private boolean touchedSneak;

    public EmoteTask(Emotes.Kind kind) {
        this(kind, false);
    }

    /** @param quiet true for a gesture the companion makes on its own: the brain hears nothing about it */
    public EmoteTask(Emotes.Kind kind, boolean quiet) {
        this.kind = kind;
        this.quiet = quiet;
    }

    @Override
    public String type() {
        return "emote";
    }

    @Override
    public void start(TaskContext c) {
        CompanionEntity companion = c.companion();
        companion.getNavigation().stop();
        baseYaw = companion.getYRot();
        sneakBefore = companion.body().sneaking();
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        CompanionEntity companion = c.companion();
        if (tick >= kind.duration()) {
            return TaskStatus.SUCCESS;
        }
        Emotes.Pose pose = Emotes.at(kind, tick++);
        companion.facing().aim(baseYaw + pose.yawOffset(), pose.pitch(), 2);
        if (pose.sneak() != companion.body().sneaking() && (pose.sneak() || touchedSneak)) {
            touchedSneak = true;
            companion.body().setSneaking(pose.sneak(), 0);
        }
        if (pose.swing()) {
            companion.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
        }
        if (pose.jump() && companion.onGround()) {
            companion.jumpFromGround();
        }
        return TaskStatus.RUNNING;
    }

    @Override
    public void stop(TaskContext c) {
        if (touchedSneak) {
            c.companion().body().setSneaking(sneakBefore, 0);
        }
    }

    @Override
    public String describe() {
        return "doing a " + kind.label().replace('_', ' ');
    }

    @Override
    public String resultSummary() {
        return "finished the " + kind.label().replace('_', ' ');
    }

    @Override
    public boolean emitsEvents() {
        return !quiet;
    }
}
