package dev.friendai.build;

import dev.friendai.pathing.Pos;

/**
 * Why a blueprint block cannot be placed where the plan asks. One typed cause instead of a prose sentence: the task
 * summary, the debug line and {@code RecoveryAdvisor} all read the same object, so a refusal always names what
 * actually blocked it and always carries a way out.
 *
 * <p>The placement-side counterpart of {@code MiningRefusal}: protection is split the same way (the server's
 * {@code mayInteract} versus a named place), and a block the companion placed itself is not foreign furniture.
 * Pure by design: it uses {@link Pos}, never a Minecraft type.
 */
public sealed interface PlacementRefusal {
    /** The cell that could not be filled. */
    Pos pos();

    /** How many bounded attempts this cell has already cost; never negative. */
    int attempts();

    /** What refused the placement, in one English sentence for the model. */
    String describe();

    /** A short label for the debug line and lists, where a whole sentence does not fit. */
    default String cause() {
        return switch (this) {
            case SelfBody r -> "my own body";
            case OtherEntity r -> r.entityId();
            case ForeignBlock r -> "foreign block";
            case OwnBlock r -> "my own block";
            case Protected r -> r.name().isBlank() ? "server protection" : "the place " + r.name();
            case NoFace r -> "nothing to click";
            case NoSupport r -> "missing support";
            case OutOfReach r -> "out of reach";
            case OutOfMaterial r -> "missing material";
            case Rejected r -> "placement refused";
            case AttemptsExhausted r -> "attempts spent";
        };
    }

    /** What the companion can do instead; never blank. */
    String remedy();

    /** The companion's body occupies the cell it has to fill. */
    record SelfBody(Pos pos, int attempts) implements PlacementRefusal {
        public SelfBody {
            attempts = clamp(attempts);
        }

        @Override
        public String describe() {
            return coords(pos) + " is where I am standing" + attemptSuffix(attempts);
        }

        @Override
        public String remedy() {
            return "step aside and retry, or resume the build at its origin";
        }
    }

    /** A mob or player stands in the cell. */
    record OtherEntity(Pos pos, String entityId, int attempts) implements PlacementRefusal {
        public OtherEntity {
            attempts = clamp(attempts);
            entityId = entityId == null || entityId.isBlank() ? "someone" : entityId;
        }

        @Override
        public String describe() {
            return coords(pos) + " is in the way: " + entityId + " stands there" + attemptSuffix(attempts);
        }

        @Override
        public String remedy() {
            return "wait for " + entityId + " to move, then retry the build";
        }
    }

    /** A block the companion did not place: the owner's furniture or terrain it may not clear. */
    record ForeignBlock(Pos pos, String blockId, int attempts) implements PlacementRefusal {
        public ForeignBlock {
            attempts = clamp(attempts);
        }

        @Override
        public String describe() {
            return "the site isn't clear: " + blockId + " at " + coords(pos) + attemptSuffix(attempts);
        }

        @Override
        public String remedy() {
            return "clear " + blockId + " or build somewhere else";
        }
    }

    /** A block the companion placed itself, from this build or an earlier one. */
    record OwnBlock(Pos pos, String blockId, boolean matchesPlan, int attempts) implements PlacementRefusal {
        public OwnBlock {
            attempts = clamp(attempts);
        }

        @Override
        public String describe() {
            return coords(pos) + " holds my own " + blockId + (matchesPlan ? ", already matching the plan" : "")
                    + attemptSuffix(attempts);
        }

        @Override
        public String remedy() {
            return matchesPlan ? "keep it" : "take it back, then place the planned block";
        }
    }

    /** The server's protection, or a place the owner cares about. An empty name means the server. */
    record Protected(Pos pos, String name, String kind, int radius, int anchorX, int anchorZ, int attempts)
            implements PlacementRefusal {
        public Protected {
            attempts = clamp(attempts);
            name = name == null ? "" : name;
            kind = kind == null ? "" : kind;
        }

        /** The server's own rule rather than a named place. */
        public boolean server() {
            return name.isBlank();
        }

        @Override
        public String describe() {
            return server()
                    ? coords(pos) + " is protected by the server (spawn protection or a world rule)"
                    : coords(pos) + " is inside the " + kind + " place \"" + name + "\" (r=" + radius + ")";
        }

        @Override
        public String remedy() {
            return server() ? "build outside the server's protected area" : "build outside " + name;
        }
    }

    /** Nothing can be clicked to place the block: every neighbour is missing or replaceable. */
    record NoFace(Pos pos, String wantedState, String detail, int attempts) implements PlacementRefusal {
        public NoFace {
            attempts = clamp(attempts);
            detail = detail == null ? "" : detail;
        }

        @Override
        public String describe() {
            return coords(pos) + " has nothing to place " + wantedState + " against"
                    + (detail.isBlank() ? "" : " (" + detail + ")") + attemptSuffix(attempts);
        }

        @Override
        public String remedy() {
            return "place a neighbouring block first, or resume the build";
        }
    }

    /** The block needs a solid neighbour that is still missing. */
    record NoSupport(Pos pos, Pos needed, boolean plannedButNotYet, int attempts) implements PlacementRefusal {
        public NoSupport {
            attempts = clamp(attempts);
        }

        @Override
        public String describe() {
            return coords(pos) + " needs a block at " + coords(needed)
                    + (plannedButNotYet ? " that the build has not placed" : " that the blueprint does not provide")
                    + attemptSuffix(attempts);
        }

        @Override
        public String remedy() {
            return plannedButNotYet
                    ? "finish the block at " + coords(needed) + ", then retry"
                    : "use a temporary support, or resume the build";
        }
    }

    /** No route or no stance from which the cell can be clicked. */
    record OutOfReach(Pos pos, String why, int attempts) implements PlacementRefusal {
        public OutOfReach {
            attempts = clamp(attempts);
            why = why == null ? "" : why;
        }

        @Override
        public String describe() {
            return "can't reach " + coords(pos) + (why.isBlank() ? "" : " (" + why + ")") + attemptSuffix(attempts);
        }

        @Override
        public String remedy() {
            return "go closer, scaffold up, or resume the build";
        }
    }

    /** A material the plan needs is not carried, and gathering did not supply it. */
    record OutOfMaterial(Pos pos, String itemId, String detail, int attempts) implements PlacementRefusal {
        public OutOfMaterial {
            attempts = clamp(attempts);
            detail = detail == null ? "" : detail;
        }

        @Override
        public String describe() {
            return "missing materials: " + itemId + (detail.isBlank() ? "" : " (" + detail + ")")
                    + attemptSuffix(attempts);
        }

        @Override
        public String remedy() {
            return "obtain " + itemId + ", then resume the build at its origin";
        }
    }

    /** Vanilla refused the placement and the cell holds something else. */
    record Rejected(Pos pos, String wantedState, String actualBlockId, int attempts) implements PlacementRefusal {
        public Rejected {
            attempts = clamp(attempts);
        }

        @Override
        public String describe() {
            return coords(pos) + " refused " + wantedState + "; the cell holds " + actualBlockId
                    + attemptSuffix(attempts);
        }

        @Override
        public String remedy() {
            return "clear that cell, then resume the build at its origin";
        }
    }

    /** The bounded recovery budget for this cell is spent. */
    record AttemptsExhausted(Pos pos, String why, int attempts) implements PlacementRefusal {
        public AttemptsExhausted {
            attempts = clamp(attempts);
            why = why == null ? "" : why;
        }

        @Override
        public String describe() {
            return coords(pos) + " is still blocked: " + why + attemptSuffix(attempts);
        }

        @Override
        public String remedy() {
            return "change the approach or resume the build at its origin";
        }
    }

    private static int clamp(int attempts) {
        return Math.max(0, attempts);
    }

    private static String attemptSuffix(int attempts) {
        return attempts <= 0 ? "" : " (attempt " + attempts + ")";
    }

    private static String coords(Pos pos) {
        return pos.x() + " " + pos.y() + " " + pos.z();
    }
}
