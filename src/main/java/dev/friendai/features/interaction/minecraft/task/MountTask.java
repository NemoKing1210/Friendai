package dev.friendai.features.interaction.minecraft.task;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.navigation.minecraft.nav.EntityTargets;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/** Walks up to a boat, minecart or rideable animal and climbs aboard. */
public final class MountTask extends ReachEntityTask {
    private static final double REACH = 2.5;

    public MountTask(Entity target) {
        super(target, REACH);
        if (target instanceof Player || target instanceof CompanionEntity) {
            throw new IllegalArgumentException("I can't ride a " + EntityTargets.label(target));
        }
    }

    @Override
    public String type() {
        return "mount";
    }

    @Override
    TaskStatus reached(TaskContext c) {
        CompanionEntity companion = c.companion();
        if (companion.getVehicle() == target) {
            summary = "riding the " + EntityTargets.label(target);
            return TaskStatus.SUCCESS;
        }
        if (!companion.startRiding(target)) {
            summary = "I can't ride the " + EntityTargets.label(target);
            return TaskStatus.FAILED;
        }
        summary = "riding the " + EntityTargets.label(target);
        return TaskStatus.SUCCESS;
    }

    @Override
    public String describe() {
        return "getting on the " + EntityTargets.label(target);
    }
}
