package dev.friendai.features.navigation.minecraft.task;

import dev.friendai.features.navigation.minecraft.nav.ResourceJourney;
import dev.friendai.foundation.spatial.Pos;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.world.movement.MoveHelper;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.world.phys.Vec3;

public final class GoToTask implements Task {
    private final Vec3 target;
    private final boolean sneaking;
    private static final double JOURNEY_DISTANCE = 64.0;

    private final MoveHelper mover = new MoveHelper(1.0, 1.5);
    private ResourceJourney journey;
    private final SneakScope sneak;
    private String summary = "";

    public GoToTask(Vec3 target) {
        this(target, false);
    }

    /** @param sneak whether to crouch for the walk, which is slower but quieter */
    public GoToTask(Vec3 target, boolean sneak) {
        this.target = target;
        this.sneaking = sneak;
        this.sneak = new SneakScope(sneak);
    }

    /** A restored walk: the destination and whether it was meant to be a quiet one. */
    public record Checkpoint(double x, double y, double z, boolean sneaking) {

        /**
         * Reads the payload. Three numbers are a walk saved before the quiet flag existed, which was an ordinary
         * walk; anything else this cannot read is not restored rather than guessed.
         */
        public static Optional<Checkpoint> decode(String data) {
            String[] parts = data == null ? new String[0] : data.split(",");
            if (parts.length != 3 && parts.length != 4) {
                return Optional.empty();
            }
            try {
                double x = Double.parseDouble(parts[0].strip());
                double y = Double.parseDouble(parts[1].strip());
                double z = Double.parseDouble(parts[2].strip());
                boolean sneaking = parts.length == 4 && "1".equals(parts[3].strip());
                return Optional.of(new Checkpoint(x, y, z, sneaking));
            } catch (NumberFormatException invalid) {
                return Optional.empty();
            }
        }
    }

    private String coords() {
        return (int) Math.floor(target.x) + " " + (int) Math.floor(target.y) + " " + (int) Math.floor(target.z);
    }

    @Override
    public String type() {
        return "go_to";
    }

    @Override
    public void start(TaskContext c) {
        sneak.enter(c);
        if (c.companion().position().distanceTo(target) <= JOURNEY_DISTANCE) {
            mover.reset(c);
            return;
        }
        var settings = c.resourceSearchSettings();
        journey = new ResourceJourney(
                c,
                c.level().dimension().identifier().toString(),
                new Pos((int) Math.floor(target.x), (int) Math.floor(target.y), (int) Math.floor(target.z)),
                null,
                c.ownerKnowledge().trails(),
                settings.allowExpeditions() && settings.chunkLoadingEnabled());
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        if (journey != null) {
            return switch (journey.tick(c)) {
                case ARRIVED -> {
                    summary = "reached " + coords();
                    yield TaskStatus.SUCCESS;
                }
                case BLOCKED, CANCELLED -> {
                    summary = "can't reach " + coords() + " across the loaded edge";
                    yield TaskStatus.FAILED;
                }
                case WAITING_FOR_CHUNK, WORKING -> TaskStatus.RUNNING;
            };
        }
        switch (mover.tick(c, target)) {
            case ARRIVED -> {
                summary = "reached " + coords();
                return TaskStatus.SUCCESS;
            }
            case STUCK -> {
                summary = "can't reach " + coords();
                return TaskStatus.FAILED;
            }
            default -> {
                return TaskStatus.RUNNING;
            }
        }
    }

    @Override
    public void stop(TaskContext c) {
        if (journey != null) journey.stop(c);
        mover.stop(c);
        c.companion().getNavigation().stop();
        sneak.exit(c);
    }

    @Override
    public void onTerminal(TaskContext c) {
        if (journey != null) journey.stop(c);
        mover.stop(c);
        sneak.exit(c);
    }

    @Override
    public String describe() {
        return "walking to " + coords();
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    @Override
    public String saveData() {
        return String.format(Locale.ROOT, "%f,%f,%f,%d", target.x, target.y, target.z, sneaking ? 1 : 0);
    }
}
