package dev.friendai.features.navigation.minecraft.task;

import dev.friendai.features.navigation.minecraft.nav.ResourceJourney;
import dev.friendai.foundation.spatial.Pos;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.world.movement.MoveHelper;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

public final class GoToTask implements Task {
    private final Vec3 target;
    private final boolean sneaking;
    /** The dimension the walk was planned in, or null for a walk that was never bound to one. */
    private final String dimension;

    private static final double JOURNEY_DISTANCE = 64.0;

    private final MoveHelper mover = new MoveHelper(1.0, 1.5);
    private ResourceJourney journey;
    private final SneakScope sneak;
    private String summary = "";
    private boolean wrongDimension;

    public GoToTask(Vec3 target) {
        this(target, false);
    }

    /** @param sneak whether to crouch for the walk, which is slower but quieter */
    public GoToTask(Vec3 target, boolean sneak) {
        this(target, sneak, null);
    }

    /**
     * @param sneak whether to crouch for the walk, which is slower but quieter
     * @param dimension the dimension the destination belongs to; a walk restored in another world is refused
     *     before it moves, and null leaves the walk unbound
     */
    public GoToTask(Vec3 target, boolean sneak, String dimension) {
        this.target = target;
        this.sneaking = sneak;
        this.dimension = dimension;
        this.sneak = new SneakScope(sneak);
    }

    /** The dimension this walk belongs to, or null when the caller did not bind one. */
    public String dimension() {
        return dimension;
    }

    /**
     * A restored walk: the destination, whether it was meant to be a quiet one, and the world it belongs to.
     */
    public record Checkpoint(double x, double y, double z, boolean sneaking, String dimension) {

        /**
         * Reads the payload. Three numbers are a walk saved before the quiet flag existed, which was an ordinary
         * walk; four numbers leave the walk unbound; a fifth part is the dimension. A dimension that is not an
         * identifier, and anything else this cannot read, is not restored rather than guessed.
         */
        public static Optional<Checkpoint> decode(String data) {
            String[] parts = data == null ? new String[0] : data.split(",");
            if (parts.length < 3 || parts.length > 5) {
                return Optional.empty();
            }
            try {
                double x = Double.parseDouble(parts[0].strip());
                double y = Double.parseDouble(parts[1].strip());
                double z = Double.parseDouble(parts[2].strip());
                boolean sneaking = parts.length >= 4 && "1".equals(parts[3].strip());
                String dimension = null;
                if (parts.length == 5) {
                    Identifier parsed = Identifier.tryParse(parts[4].strip());
                    if (parsed == null) {
                        return Optional.empty();
                    }
                    dimension = parsed.toString();
                }
                return Optional.of(new Checkpoint(x, y, z, sneaking, dimension));
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
        if (dimension != null
                && !dimension.equals(c.level().dimension().identifier().toString())) {
            // The destination describes another world: opening a route here would walk it to meaningless numbers.
            wrongDimension = true;
            summary = "can't walk to " + coords() + ": it is in " + dimension + ", not in "
                    + c.level().dimension().identifier();
            return;
        }
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
        if (wrongDimension) {
            return TaskStatus.FAILED;
        }
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
        String walk = String.format(Locale.ROOT, "%f,%f,%f,%d", target.x, target.y, target.z, sneaking ? 1 : 0);
        return dimension == null ? walk : walk + "," + dimension;
    }
}
