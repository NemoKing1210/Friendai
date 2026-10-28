package dev.friendai.task.mining;

import dev.friendai.place.Place;
import dev.friendai.place.PlaceKind;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskSettings;
import dev.friendai.task.autonomy.PlaceGuard;
import dev.friendai.task.nav.LevelCellView;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Why the companion may not break a block. A single typed cause instead of one "protected": the task summary,
 * the debug target and the recovery advice all render the same sentence, so the model learns which rule refused
 * it and what it can do instead.
 *
 * <p>{@link #check} is the only classifier. {@link BreakAction} and {@link BlockBreaker} carry its result; every
 * caller that used to print "is protected" prints {@link #describe()} and {@link #remedy()} instead.
 */
public sealed interface MiningRefusal {
    /** The block that was refused. */
    BlockPos pos();

    /** What refused it, in one model-facing sentence. */
    String describe();

    /** What the companion can do instead; never empty. */
    String remedy();

    /** A short label for lists and debug lines, where a whole sentence does not fit. */
    default String cause() {
        return switch (this) {
            case ServerProtected p -> "server protection";
            case PlaceProtected p -> "inside " + p.name();
            case OwnSupport p -> p.safeDrop() ? "under my feet" : "unsafe drop under my feet";
            case Blacklisted p -> "break list";
            case Unbreakable p -> "unbreakable";
            case OutOfReach p -> "out of reach";
        };
    }

    /**
     * The refusal for {@code pos}, or null when the companion may break it.
     *
     * @param allowSupport whether the caller deliberately asked to break the block under the companion; an unsafe
     *     drop is still refused
     */
    static @Nullable MiningRefusal check(TaskContext c, BlockPos pos, boolean allowSupport) {
        var companion = c.companion();
        var level = c.level();
        if (!companion.hands().canInteract(pos)) {
            return new ServerProtected(pos);
        }
        if (SupportSafety.occupies(companion, pos)) {
            boolean safe = SupportSafety.safeDrop(level, pos);
            if (!allowSupport || !safe) {
                return new OwnSupport(pos, safe);
            }
        }
        BlockState state = level.getBlockState(pos);
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        boolean creative = companion.creativeMode();
        if (creative && TaskSettings.breakList.matches(blockId, LevelCellView.tagsOf(state))) {
            return new Blacklisted(pos, blockId);
        }
        if (!creative && state.getDestroySpeed(level, pos) < 0) {
            return new Unbreakable(pos, blockId);
        }
        Place place = PlaceGuard.protecting(c, pos);
        if (place != null) {
            return new PlaceProtected(
                    pos,
                    place.name(),
                    place.kind(),
                    place.radius(),
                    place.anchor().x(),
                    place.anchor().z());
        }
        return null;
    }

    /** The server's own protection: spawn protection and world rules say no. */
    record ServerProtected(BlockPos pos) implements MiningRefusal {
        @Override
        public String describe() {
            return coords(pos) + " is protected by the server (spawn protection or a world rule)";
        }

        @Override
        public String remedy() {
            return "mine outside the server's protected area";
        }
    }

    /** A place the owner cares about; the sentence names it, and the remedy says which way the edge is. */
    record PlaceProtected(BlockPos pos, String name, PlaceKind kind, int radius, int anchorX, int anchorZ)
            implements MiningRefusal {
        @Override
        public String describe() {
            return coords(pos) + " is inside the " + kind.name().toLowerCase(Locale.ROOT) + " place \"" + name
                    + "\" (r=" + radius + ")";
        }

        @Override
        public String remedy() {
            int dx = pos.getX() - anchorX;
            int dz = pos.getZ() - anchorZ;
            int fromAnchor = Math.max(Math.abs(dx), Math.abs(dz));
            int outside = Math.max(1, radius + 1 - fromAnchor);
            String direction;
            if (Math.abs(dx) >= Math.abs(dz)) {
                direction = dx >= 0 ? "east" : "west";
            } else {
                direction = dz >= 0 ? "south" : "north";
            }
            return "mine outside " + name + ": " + outside + " blocks " + direction;
        }
    }

    /** The block the companion stands on: breaking it drops the companion as well. */
    record OwnSupport(BlockPos pos, boolean safeDrop) implements MiningRefusal {
        @Override
        public String describe() {
            return coords(pos) + " is the block I am standing on"
                    + (safeDrop ? "" : " and the drop below is not safe (lava or a long fall)");
        }

        @Override
        public String remedy() {
            return safeDrop
                    ? "break it and drop one block, or mine a neighbouring block"
                    : "mine a neighbouring block instead";
        }
    }

    /** The creative break list forbids this block. */
    record Blacklisted(BlockPos pos, String blockId) implements MiningRefusal {
        @Override
        public String describe() {
            return coords(pos) + " is on the break list (" + blockId + ")";
        }

        @Override
        public String remedy() {
            return "mine a block that is not on the break list";
        }
    }

    /** The block can never be broken by a survival companion. */
    record Unbreakable(BlockPos pos, String blockId) implements MiningRefusal {
        @Override
        public String describe() {
            return coords(pos) + " cannot be broken (" + blockId + ")";
        }

        @Override
        public String remedy() {
            return "choose another block";
        }
    }

    /** No route or no stance from which the block can be hit. */
    record OutOfReach(BlockPos pos, String why) implements MiningRefusal {
        @Override
        public String describe() {
            return "can't reach " + coords(pos) + (why == null || why.isBlank() ? "" : " (" + why + ")");
        }

        @Override
        public String remedy() {
            return "go closer or approach from another side";
        }
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
